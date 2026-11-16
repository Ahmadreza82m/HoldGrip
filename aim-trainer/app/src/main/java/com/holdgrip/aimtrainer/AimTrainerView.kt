package com.holdgrip.aimtrainer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.sin

class AimTrainerView(context: Context, private val onStats: (Int, Int) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hits = 0
    private var misses = 0
    private var startedAt = System.currentTimeMillis()
    private var paused = false
    private var targetX = 0f
    private var targetY = 0f
    private var radius = 34f
    private var phase = 0.0
    private val bounds = RectF()

    init {
        isClickable = true
        setBackgroundColor(0xFF171D29.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        paint.color = 0xFF293449.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        val step = 64f
        var x = 0f
        while (x < w) { canvas.drawLine(x, 0f, x, h, paint); x += step }
        var y = 0f
        while (y < h) { canvas.drawLine(0f, y, w, y, paint); y += step }
        if (targetX == 0f) {
            targetX = w * .5f
            targetY = h * .5f
        }
        if (!paused) {
            phase += .035
            targetX = (w * .5f + sin(phase) * w * .31).toFloat().coerceIn(radius, w - radius)
            targetY = (h * .5f + sin(phase * .73 + 1.2) * h * .29).toFloat().coerceIn(radius, h - radius)
            postInvalidateDelayed(16)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0xFFFF647C.toInt()
        canvas.drawCircle(targetX, targetY, radius + 10f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFF647C.toInt()
        canvas.drawCircle(targetX, targetY, radius, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(targetX, targetY, radius * .34f, paint)
        paint.color = 0xFF17202D.toInt()
        canvas.drawCircle(targetX, targetY, radius * .12f, paint)
        paint.color = 0xFFB8C0CF.toInt()
        paint.textSize = 13f
        canvas.drawText("Tap the moving target", 16f, 26f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val distance = hypot(event.x - targetX, event.y - targetY)
        if (distance <= radius + 12f) {
            hits++
            radius = (radius - 0.5f).coerceAtLeast(20f)
        } else {
            misses++
            radius = (radius + 0.35f).coerceAtMost(48f)
        }
        onStats(hits, misses)
        invalidate()
        return true
    }

    fun resetSession() {
        hits = 0
        misses = 0
        radius = 34f
        phase = 0.0
        startedAt = System.currentTimeMillis()
        onStats(hits, misses)
        invalidate()
    }

    fun pauseAnimation() { paused = true; invalidate() }
    fun resumeAnimation() { paused = false; invalidate() }
}
