package com.holdgrip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class AccessService : AccessibilityService() {

    companion object {
        var instance: AccessService? = null
    }

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var button: View? = null
    private var editor: View? = null
    private var screenW = 0
    private var screenH = 0

    // وضعیت ژست
    private var held = false
    private var running = false
    private var finishing = false
    private var lastStroke: StrokeDescription? = null
    private var curX = 0f
    private var curY = 0f
    private var releaseAttempts = 0
    private var activePointerId = -1

    private val boxSize: Int
        get() = (prefs.buttonSizeDp * resources.displayMetrics.density).toInt()

    private val overlayFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (prefs.showButton) showButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        finishHold()
        hideButton()
        hideEditor()
        instance = null
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------
    // ابزارهای کمکی
    // ------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun readScreen() {
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels
    }

    private fun overlayParams(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            overlayFlags,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x
        p.y = y
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    private fun roundBg(color: Int, radius: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radius
        return d
    }

    private fun circleBg(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        return d
    }

    // ------------------------------------------------------------
    // دکمه شناور
    // ------------------------------------------------------------

    private var buttonParams: WindowManager.LayoutParams? = null

    fun showButton() {
        hideButton()
        readScreen()
        val size = boxSize
        val v = View(this)
        val silver = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFF1F3F5.toInt(), 0xFF9CA3AF.toInt(), 0xFFDDE1E6.toInt()))
        silver.shape = GradientDrawable.OVAL
        silver.setStroke((2 * resources.displayMetrics.density).toInt(), 0xFF626B76.toInt())
        v.background = silver
        v.elevation = 8f * resources.displayMetrics.density
        v.alpha = prefs.buttonAlpha.coerceIn(0.2f, 1f)

        val x = (prefs.btnX * screenW - size / 2f).toInt().coerceIn(0, screenW - size)
        val y = (prefs.btnY * screenH - size / 2f).toInt().coerceIn(0, screenH - size)
        val bp = overlayParams(size, size, x, y)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0

        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = e.getPointerId(0)
                    if (prefs.locked) {
                        startHold()
                    } else {
                        downX = e.rawX
                        downY = e.rawY
                        startX = bp.x
                        startY = bp.y
                    }
                }
                MotionEvent.ACTION_MOVE -> if (!prefs.locked) {
                    bp.x = (startX + (e.rawX - downX).toInt()).coerceIn(0, screenW - size)
                    bp.y = (startY + (e.rawY - downY).toInt()).coerceIn(0, screenH - size)
                    try { wm.updateViewLayout(v, bp) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (prefs.locked) {
                        releaseHold()
                    } else {
                        prefs.btnX = (bp.x + size / 2f) / screenW
                        prefs.btnY = (bp.y + size / 2f) / screenH
                    }
                    activePointerId = -1
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // فقط بالا رفتن همان انگشتی که دکمه را پایین زده، نگه‌داری را تمام می‌کند.
                    if (prefs.locked && e.getPointerId(e.actionIndex) == activePointerId) {
                        releaseHold()
                        activePointerId = -1
                    }
                }
            }
            true
        }

        try {
            wm.addView(v, bp)
            button = v
            buttonParams = bp
        } catch (_: Exception) {
            hideButton()
        }
    }

    fun refreshButtonAppearance() {
        val v = button ?: return
        val p = buttonParams ?: return
        val size = boxSize
        p.width = size
        p.height = size
        v.alpha = prefs.buttonAlpha.coerceIn(0.2f, 1f)
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    fun hideButton() {
        finishHold()
        val v = button
        if (v != null) {
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        button = null
        buttonParams = null
    }

    // ------------------------------------------------------------
    // ژست نگه‌داشتن پیوسته
    // A -> B با willContinue = true، بعد بلوک‌های ۱۰۰ میلی‌ثانیه‌ای
    // تا رها شدن دکمه، و در آخر یک بلوک کوتاه با willContinue = false
    // ------------------------------------------------------------

    private fun startHold() {
        if (running) return
        running = true
        held = true
        finishing = false
        readScreen()

        val ax = prefs.ax * screenW
        val ay = prefs.ay * screenH
        val bx = prefs.bx * screenW
        val by = prefs.by * screenH

        curX = bx
        curY = by
        val path = Path()
        path.moveTo(ax, ay)
        path.lineTo(bx, by)

        val duration = prefs.swipeMs.toLong().coerceAtLeast(1L)
        val stroke = StrokeDescription(path, 0L, duration, true)
        lastStroke = stroke
        send(stroke)
    }

    private fun send(stroke: StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (finishing) reset() else next()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                // فقط در مسیر خطا از ژست مستقل برای آزادسازی اضطراری استفاده می‌شود.
                held = false
                forceRelease()
            }
        }, handler)
        if (!ok) {
            held = false
            forceRelease()
        }
    }

    private fun next() {
        val last = lastStroke
        if (last == null) {
            reset()
            return
        }
        // در زمان نگه‌داری، زنجیره بدون وقفه روی B ادامه پیدا می‌کند.
        // استروک پایانی یک حرکت یک‌پیکسلی ۵۰ میلی‌ثانیه‌ای است تا رهاسازی ثبت شود.
        val keepGoing = held
        val path = Path()
        path.moveTo(curX, curY)
        if (!keepGoing) path.lineTo(curX + 1f, curY)

        finishing = !keepGoing
        val duration = if (keepGoing) 200L else 50L
        try {
            val stroke = last.continueStroke(path, 0L, duration, keepGoing)
            lastStroke = stroke
            send(stroke)
        } catch (_: Exception) {
            held = false
            forceRelease()
        }
    }

    private fun releaseHold() {
        // زنجیره فعلی را قطع نکن؛ callback بلوک جاری، next() را اجرا می‌کند
        // و next() همان StrokeDescription را با willContinue=false خاتمه می‌دهد.
        held = false
    }

    private fun finishHold() {
        // حتی هنگام بسته‌شدن overlay نیز اجازه بده زنجیره فعلی به استروک نهایی برسد.
        releaseHold()
    }

    // ارسال یک ژست مستقل برای بالا بردن انگشت مجازی در نقطه B.
    // در صورت رد یا لغو شدن، چند بار با فاصله کوتاه تلاش می‌کنیم.
    private fun forceRelease() {
        if (!running) return
        held = false
        finishing = true
        if (releaseAttempts >= 4) {
            reset()
            return
        }

        releaseAttempts++
        val path = Path().apply {
            moveTo(curX, curY)
            lineTo(curX + 1f, curY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(StrokeDescription(path, 0L, 50L, false))
            .build()

        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                reset()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (running) handler.postDelayed({ forceRelease() }, 60L)
            }
        }, handler)

        if (!accepted && running) {
            handler.postDelayed({ forceRelease() }, 60L)
        }
    }

    private fun reset() {
        running = false
        held = false
        finishing = false
        releaseAttempts = 0
        activePointerId = -1
        lastStroke = null
    }

    // ------------------------------------------------------------
    // صفحه تنظیم نقاط: سه دایره قابل درگ (شروع، پایان، دکمه)
    // ------------------------------------------------------------

    private class LineView(c: Context, val a: View, val b: View, val half: Float) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 6f
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawLine(a.x + half, a.y + half, b.x + half, b.y + half, paint)
        }
    }

    private fun marker(label: String, color: Int, size: Int): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 12f
        t.gravity = Gravity.CENTER
        t.isClickable = true
        t.isFocusable = false
        val bg = circleBg(color)
        bg.setStroke((3 * resources.displayMetrics.density).toInt(), Color.WHITE)
        t.background = bg
        t.elevation = 16f * resources.displayMetrics.density
        t.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT)
        return t
    }

    private fun setMarkerPosition(root: FrameLayout, marker: View, left: Int, top: Int, size: Int) {
        val lp = marker.layoutParams as FrameLayout.LayoutParams
        lp.width = size
        lp.height = size
        lp.leftMargin = left.coerceIn(0, (root.width - size).coerceAtLeast(0))
        lp.topMargin = top.coerceIn(0, (root.height - size).coerceAtLeast(0))
        marker.layoutParams = lp
    }

    private fun makeMarkerDraggable(root: FrameLayout, marker: View, size: Int, onMove: () -> Unit) {
        var downRawX = 0f
        var downRawY = 0f
        var initialLeft = 0
        var initialTop = 0
        marker.setOnTouchListener { view, event ->
            val lp = view.layoutParams as FrameLayout.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    initialLeft = lp.leftMargin
                    initialTop = lp.topMargin
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val left = initialLeft + (event.rawX - downRawX).toInt()
                    val top = initialTop + (event.rawY - downRawY).toInt()
                    setMarkerPosition(root, view, left, top, size)
                    onMove()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onMove(); true }
                else -> true
            }
        }
    }
    private fun pill(text: String, color: Int): TextView {
        val d = resources.displayMetrics.density
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.textSize = 16f
        t.gravity = Gravity.CENTER
        t.setPadding((20 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
        t.background = roundBg(color, 100f)
        return t
    }

    fun showEditor() {
        finishHold()
        hideEditor()
        hideButton()
        readScreen()
        val d = resources.displayMetrics.density
        val markerSize = (72 * d).toInt().coerceAtLeast(56)
        val root = FrameLayout(this)
        root.setBackgroundColor(0x990B1220.toInt())
        root.isClickable = true

        val startMarker = marker(getString(R.string.marker_a), 0xFF16A34A.toInt(), markerSize)
        val endMarker = marker(getString(R.string.marker_b), 0xFFDC2626.toInt(), markerSize)
        val buttonMarker = marker(getString(R.string.marker_btn), 0xFF7B8490.toInt(), markerSize)
        val line = LineView(this, startMarker, endMarker, markerSize / 2f)
        root.addView(line, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(startMarker)
        root.addView(endMarker)
        root.addView(buttonMarker)

        val hint = pill(getString(R.string.editor_hint), 0xEE111827.toInt())
        val save = pill(getString(R.string.save), 0xFF16A34A.toInt())
        val cancel = pill(getString(R.string.cancel), 0xFF4B5563.toInt())
        save.setOnClickListener {
            val w = root.width.coerceAtLeast(1).toFloat()
            val h = root.height.coerceAtLeast(1).toFloat()
            val aLp = startMarker.layoutParams as FrameLayout.LayoutParams
            val bLp = endMarker.layoutParams as FrameLayout.LayoutParams
            val btnLp = buttonMarker.layoutParams as FrameLayout.LayoutParams
            prefs.ax = ((aLp.leftMargin + markerSize / 2f) / w).coerceIn(0f, 1f)
            prefs.ay = ((aLp.topMargin + markerSize / 2f) / h).coerceIn(0f, 1f)
            prefs.bx = ((bLp.leftMargin + markerSize / 2f) / w).coerceIn(0f, 1f)
            prefs.by = ((bLp.topMargin + markerSize / 2f) / h).coerceIn(0f, 1f)
            prefs.btnX = ((btnLp.leftMargin + markerSize / 2f) / w).coerceIn(0f, 1f)
            prefs.btnY = ((btnLp.topMargin + markerSize / 2f) / h).coerceIn(0f, 1f)
            closeEditor()
        }
        cancel.setOnClickListener { closeEditor() }

        val m = (6 * d).toInt()
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(save, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(m, m, m, m) })
        bar.addView(cancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(m, m, m, m) })
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(hint)
        col.addView(bar)
        val colParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        colParams.topMargin = (24 * d).toInt()
        root.addView(col, colParams)

        try {
            val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, 0, 0)
            wm.addView(root, params)
            editor = root
            // Wait for the overlay to be measured before placing the markers.
            root.post {
                if (editor !== root) return@post
                val w = root.width.coerceAtLeast(1)
                val h = root.height.coerceAtLeast(1)
                setMarkerPosition(root, startMarker, (prefs.ax * w - markerSize / 2f).toInt(), (prefs.ay * h - markerSize / 2f).toInt(), markerSize)
                setMarkerPosition(root, endMarker, (prefs.bx * w - markerSize / 2f).toInt(), (prefs.by * h - markerSize / 2f).toInt(), markerSize)
                setMarkerPosition(root, buttonMarker, (prefs.btnX * w - markerSize / 2f).toInt(), (prefs.btnY * h - markerSize / 2f).toInt(), markerSize)
                makeMarkerDraggable(root, startMarker, markerSize) { line.invalidate() }
                makeMarkerDraggable(root, endMarker, markerSize) { line.invalidate() }
                makeMarkerDraggable(root, buttonMarker, markerSize) { line.invalidate() }
                line.invalidate()
            }
        } catch (e: Exception) {
            editor = null
            Toast.makeText(this, "نمایش صفحه تنظیم نقاط ناموفق بود: " + e.message, Toast.LENGTH_LONG).show()
            if (prefs.showButton) showButton()
        }
    }
    private fun hideEditor() {
        finishHold()
        val v = editor ?: return
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
        editor = null
    }

    private fun closeEditor() {
        hideEditor()
        if (prefs.showButton) showButton()
    }
}
