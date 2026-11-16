package com.holdgrip.aimtrainer

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AimTrainerActivity : Activity() {
    private lateinit var stats: TextView
    private lateinit var trainer: AimTrainerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 18, 14, 14)
            setBackgroundColor(0xFF10131B.toInt())
        }
        val title = TextView(this).apply {
            text = "AIM PRACTICE"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(0xFFF2F4F8.toInt())
            setPadding(0, 4, 0, 10)
        }
        stats = TextView(this).apply {
            text = "Hits: 0   Misses: 0   Accuracy: 0%"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFB8C0CF.toInt())
            setPadding(0, 8, 0, 12)
        }
        trainer = AimTrainerView(this) { hits, misses ->
            val total = hits + misses
            val accuracy = if (total == 0) 0 else hits * 100 / total
            stats.text = "Hits: $hits   Misses: $misses   Accuracy: $accuracy%"
        }
        val reset = Button(this).apply {
            text = "Reset session"
            setOnClickListener { trainer.resetSession() }
        }
        val note = TextView(this).apply {
            text = "Practice accuracy is measured only inside this trainer. It is not an in-game headshot percentage."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF8E9AAF.toInt())
            setPadding(6, 10, 6, 4)
        }
        root.addView(title)
        root.addView(stats)
        root.addView(trainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(reset)
        root.addView(note)
        setContentView(root)
    }

    override fun onPause() {
        super.onPause()
        if (::trainer.isInitialized) trainer.pauseAnimation()
    }

    override fun onResume() {
        super.onResume()
        if (::trainer.isInitialized) trainer.resumeAnimation()
    }
}
