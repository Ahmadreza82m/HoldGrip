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

    private var button: View? = null
    private var editor: View? = null
    private var screenW = 0
    private var screenH = 0

    // مختصات نقطه پایان و شناسه انگشت واقعی روی دکمه شناور.
    private var curX = 0f
    private var curY = 0f
    private var activePointerId = -1
    // Run one separate endpoint tap only after the held stroke has been released.
    private var releaseTapPending = false

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

    override fun onInterrupt() {
        finishHold()
    }

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

    // رنگ دکمه فقط برای تشخیص مرحله‌ای است که لمس مجازی در آن قرار دارد:
    // آبی=نگه‌داشته‌شده، نارنجی=درخواست رهاسازی، سبز=رهاسازی کامل، قرمز=لغو/خطا.
    private fun setButtonDiagnosticState(state: String) {
        val v = button ?: return
        val density = resources.displayMetrics.density
        val bg = when (state) {
            "pressed" -> circleBg(0xFF3B82F6.toInt())
            "release_requested" -> circleBg(0xFFF59E0B.toInt())
            "released" -> circleBg(0xFF22C55E.toInt())
            "error" -> circleBg(0xFFEF4444.toInt())
            "stroke_cancelled" -> circleBg(0xFF8B5CF6.toInt())
            "final_cancelled" -> circleBg(0xFFDC2626.toInt())
            "dispatch_rejected" -> circleBg(0xFFEC4899.toInt())
            "continuation_exception" -> circleBg(0xFFF97316.toInt())
            else -> GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFF1F3F5.toInt(), 0xFF9CA3AF.toInt(), 0xFFDDE1E6.toInt())
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke((2 * density).toInt(), 0xFF626B76.toInt())
            }
        }
        if (state != "idle") bg.setStroke((2 * density).toInt(), 0xFF626B76.toInt())
        v.post { v.background = bg }
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
        var dragging = false
        val touchSlop = 10f

        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = e.getPointerId(0)
                    downX = e.rawX
                    downY = e.rawY
                    startX = bp.x
                    startY = bp.y
                    dragging = false
                    android.util.Log.d("HoldGrip", "ACTION_DOWN pointerId=$activePointerId locked=${prefs.locked}")
                    startHold()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.findPointerIndex(activePointerId) >= 0) {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!prefs.locked && !dragging &&
                            (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)
                        ) {
                            dragging = true
                            releaseHold()
                        }
                        if (!prefs.locked && dragging) {
                            bp.x = (startX + dx.toInt()).coerceIn(0, screenW - size)
                            bp.y = (startY + dy.toInt()).coerceIn(0, screenH - size)
                            try { wm.updateViewLayout(v, bp) } catch (_: Exception) {}
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    android.util.Log.d("HoldGrip", "Floating button released: action=UP")
                    releaseHold(tapAfterRelease = !dragging)
                    if (dragging) {
                        prefs.btnX = (bp.x + size / 2f) / screenW
                        prefs.btnY = (bp.y + size / 2f) / screenH
                    }
                    activePointerId = -1
                }
                MotionEvent.ACTION_CANCEL -> {
                    android.util.Log.d("HoldGrip", "Floating button cancelled")
                    releaseHold()
                    activePointerId = -1
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (e.getPointerId(e.actionIndex) == activePointerId) {
                        releaseHold(tapAfterRelease = !dragging)
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
    // ژست پیوسته A -> B؛ چرخه عمر در HoldChain مدیریت می‌شود.

    private val mainHandler = Handler(Looper.getMainLooper())

    private val chainPort = object : HoldChain.Port<StrokeDescription> {
        override fun begin(durationMs: Long): StrokeDescription {
            readScreen()
            val ax = prefs.ax * screenW
            val ay = prefs.ay * screenH
            curX = prefs.bx * screenW
            curY = prefs.by * screenH
            val dx = curX - ax
            val dy = curY - ay
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            val bend = prefs.curve.coerceIn(-1f, 1f) * distance * 0.45f
            val path = Path().apply {
                moveTo(ax, ay)
                if (distance > 1f) {
                    val normalX = -dy / distance
                    val normalY = dx / distance
                    quadTo(
                        (ax + curX) / 2f + normalX * bend,
                        (ay + curY) / 2f + normalY * bend,
                        curX,
                        curY
                    )
                } else {
                    lineTo(curX, curY)
                }
            }
            return StrokeDescription(path, 0L, durationMs, true)
        }

        override fun cont(prev: StrokeDescription, durationMs: Long, willContinue: Boolean): StrokeDescription {
            val stationaryPath = Path().apply { moveTo(curX, curY) }
            return prev.continueStroke(stationaryPath, 0L, durationMs, willContinue)
        }

        override fun dispatch(stroke: StrokeDescription, cb: HoldChain.Callback): Boolean {
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = cb.onCompleted()
                override fun onCancelled(gestureDescription: GestureDescription?) = cb.onCancelled()
            }, null)
        }

        override fun post(delayMs: Long, r: () -> Unit): HoldChain.Cancelable {
            val task = Runnable { r() }
            mainHandler.postDelayed(task, delayMs)
            return HoldChain.Cancelable { mainHandler.removeCallbacks(task) }
        }
    }

    private val chain = HoldChain(chainPort) { message ->
        android.util.Log.d("HoldGrip", message)
        when {
            message.startsWith("release:") -> setButtonDiagnosticState("release_requested")
            message == "final UP confirmed" -> {
                setButtonDiagnosticState("released")
                dispatchReleaseTapIfPending()
            }
            message.startsWith("cancelled by system") -> {
                setButtonDiagnosticState("stroke_cancelled")
                if (releaseTapPending) dispatchReleaseTapIfPending()
            }
            message.startsWith("callback LOST") -> {
                releaseTapPending = false
                setButtonDiagnosticState("error")
            }
            message.contains("FAILED") || message.contains("threw") -> {
                releaseTapPending = false
                setButtonDiagnosticState("error")
            }
            message == "first dispatch rejected" -> setButtonDiagnosticState("dispatch_rejected")
        }
    }

    private fun startHold() {
        if (chain.press(prefs.swipeMs.toLong())) {
            setButtonDiagnosticState("pressed")
        } else {
            android.util.Log.w("HoldGrip", "Press ignored or gesture dispatch failed")
        }
    }

    private fun releaseHold(tapAfterRelease: Boolean = false) {
        // Queue the extra tap only for a normal finger-up, never for drag/cancel.
        if (tapAfterRelease && chain.held) releaseTapPending = true
        chain.release()
    }

    private fun dispatchReleaseTapIfPending() {
        if (!releaseTapPending) return
        releaseTapPending = false

        // Wait until the continuous gesture's final UP callback has completed.
        mainHandler.postDelayed({
            val x = curX.coerceIn(0f, (screenW - 2).coerceAtLeast(1).toFloat())
            val y = curY.coerceIn(0f, (screenH - 2).coerceAtLeast(1).toFloat())
            val tapPath = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y)
            }
            try {
                val tap = GestureDescription.Builder()
                    .addStroke(StrokeDescription(tapPath, 0L, 55L))
                    .build()
                val accepted = dispatchGesture(tap, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        android.util.Log.d("HoldGrip", "endpoint tap completed")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        android.util.Log.w("HoldGrip", "endpoint tap cancelled")
                    }
                }, null)
                android.util.Log.d("HoldGrip", "endpoint tap dispatch accepted=$accepted at ($x,$y)")
                if (!accepted) setButtonDiagnosticState("error")
            } catch (error: Exception) {
                android.util.Log.e("HoldGrip", "endpoint tap failed", error)
                setButtonDiagnosticState("error")
            }
        }, 20L)
    }

    private fun finishHold() {
        releaseTapPending = false
        chain.abort()
    }

    // ------------------------------------------------------------
    // صفحه تنظیم نقاط: سه دایره قابل درگ (شروع، پایان، دکمه)
    // ------------------------------------------------------------

    private class LineView(
        c: Context,
        val a: View,
        val b: View,
        val half: Float,
        private val curve: () -> Float
    ) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 6f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val ax = a.x + half
            val ay = a.y + half
            val bx = b.x + half
            val by = b.y + half
            val dx = bx - ax
            val dy = by - ay
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            val bend = curve().coerceIn(-1f, 1f) * distance * 0.45f
            val path = Path().apply {
                moveTo(ax, ay)
                if (distance > 1f) {
                    val normalX = -dy / distance
                    val normalY = dx / distance
                    quadTo(
                        (ax + bx) / 2f + normalX * bend,
                        (ay + by) / 2f + normalY * bend,
                        bx,
                        by
                    )
                } else {
                    lineTo(bx, by)
                }
            }
            canvas.drawPath(path, paint)
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
        val line = LineView(this, startMarker, endMarker, markerSize / 2f) { prefs.curve }
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
            ProfileStore(this@AccessService, prefs).saveCurrent()
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
