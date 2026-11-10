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
        v.background = circleBg(Color.WHITE)
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
                        // با رها شدن انگشت، حلقه ادامه ژست متوقف و استروک نهایی ارسال می‌شود.
                        held = false
                    } else {
                        prefs.btnX = (bp.x + size / 2f) / screenW
                        prefs.btnY = (bp.y + size / 2f) / screenH
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
                reset()
            }
        }, handler)
        if (!ok) reset()
    }

    private fun next() {
        val last = lastStroke
        if (last == null) {
            reset()
            return
        }
        // نگه‌داری دقیقاً روی نقطه B، بدون هیچ حرکتی (فقط یک نقطه)
        val path = Path()
        path.moveTo(curX, curY)

        val keepGoing = held
        finishing = !keepGoing
        val duration = if (keepGoing) 200L else 10L
        val stroke = last.continueStroke(path, 0L, duration, keepGoing)
        lastStroke = stroke
        send(stroke)
    }

    private fun reset() {
        running = false
        held = false
        finishing = false
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

    private fun marker(
        label: String,
        color: Int,
        fx: Float,
        fy: Float,
        size: Int,
        onMove: () -> Unit
    ): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 12f
        t.gravity = Gravity.CENTER
        t.background = circleBg(color)
        t.layoutParams = FrameLayout.LayoutParams(size, size)
        t.x = fx * screenW - size / 2f
        t.y = fy * screenH - size / 2f

        var dx = 0f
        var dy = 0f
        t.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = v.x - e.rawX
                    dy = v.y - e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    v.x = (e.rawX + dx).coerceIn(0f, (screenW - size).toFloat())
                    v.y = (e.rawY + dy).coerceIn(0f, (screenH - size).toFloat())
                    onMove()
                }
            }
            true
        }
        return t
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
        hideEditor()
        hideButton()
        readScreen()

        val d = resources.displayMetrics.density
        val size = boxSize

        val root = FrameLayout(this)
        root.setBackgroundColor(0x66000000)

        val a = marker(getString(R.string.marker_a), 0xFF22C55E.toInt(), prefs.ax, prefs.ay, size) {
            root.getChildAt(0)?.invalidate()
        }
        val b = marker(getString(R.string.marker_b), 0xFFEF4444.toInt(), prefs.bx, prefs.by, size) {
            root.getChildAt(0)?.invalidate()
        }
        val btn = marker(getString(R.string.marker_btn), 0xFF8B5CF6.toInt(), prefs.btnX, prefs.btnY, size) {}

        // خط بین نقطه شروع و پایان (باید اولین فرزند باشد)
        root.addView(
            LineView(this, a, b, size / 2f),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        root.addView(a)
        root.addView(b)
        root.addView(btn)

        val hint = pill(getString(R.string.editor_hint), 0xCC000000.toInt())
        val save = pill(getString(R.string.save), 0xFF22C55E.toInt())
        val cancel = pill(getString(R.string.cancel), 0xFF6B7280.toInt())

        save.setOnClickListener {
            prefs.ax = ((a.x + size / 2f) / screenW).coerceIn(0f, 1f)
            prefs.ay = ((a.y + size / 2f) / screenH).coerceIn(0f, 1f)
            prefs.bx = ((b.x + size / 2f) / screenW).coerceIn(0f, 1f)
            prefs.by = ((b.y + size / 2f) / screenH).coerceIn(0f, 1f)
            prefs.btnX = ((btn.x + size / 2f) / screenW).coerceIn(0f, 1f)
            prefs.btnY = ((btn.y + size / 2f) / screenH).coerceIn(0f, 1f)
            closeEditor()
        }
        cancel.setOnClickListener { closeEditor() }

        val m = (6 * d).toInt()
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(save, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(m, m, m, m) })
        bar.addView(cancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(m, m, m, m) })

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(hint)
        col.addView(bar)

        val colParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        colParams.topMargin = (72 * d).toInt()
        root.addView(col, colParams)

        try {
            wm.addView(root, overlayParams(screenW, screenH, 0, 0))
            editor = root
        } catch (e: Exception) {
            editor = null
            if (prefs.showButton) showButton()
        }
    }

    private fun hideEditor() {
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
