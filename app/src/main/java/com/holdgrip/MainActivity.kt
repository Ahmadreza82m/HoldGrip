package com.holdgrip

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.setText(
            if (AccessService.instance != null) R.string.status_on else R.string.status_off
        )
        toggle.setText(if (prefs.showButton) R.string.hide_button else R.string.show_button)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun button(textRes: Int, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.setText(textRes)
        t.setTextColor(Color.WHITE)
        t.textSize = 16f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(16), dp(14), dp(16), dp(14))
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(12).toFloat()
        t.background = bg
        t.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(12)
        t.layoutParams = lp
        return t
    }

    private fun buildUi(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(40), dp(24), dp(24))
        col.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val title = TextView(this)
        title.setText(R.string.title)
        title.textSize = 24f
        title.setTextColor(Color.BLACK)
        col.addView(title)

        val usage = TextView(this)
        usage.setText(R.string.usage)
        usage.textSize = 14f
        usage.setPadding(0, dp(8), 0, dp(8))
        col.addView(usage)

        status = TextView(this)
        status.textSize = 16f
        status.setTextColor(Color.BLACK)
        status.setPadding(0, dp(8), 0, 0)
        col.addView(status)

        col.addView(button(R.string.enable_accessibility, 0xFF6B7280.toInt()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(button(R.string.edit_points, 0xFF8B5CF6.toInt()) {
            val service = AccessService.instance
            if (service == null) {
                Toast.makeText(this, R.string.need_service, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.edit_wait, Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
                Handler(Looper.getMainLooper()).postDelayed({
                    AccessService.instance?.showEditor()
                }, 4000)
            }
        })

        toggle = button(R.string.hide_button, 0xFF6B7280.toInt()) {
            prefs.showButton = !prefs.showButton
            if (prefs.showButton) {
                AccessService.instance?.showButton()
            } else {
                AccessService.instance?.hideButton()
            }
            refresh()
        }
        col.addView(toggle)

        val label = TextView(this)
        label.setText(R.string.swipe_duration)
        label.textSize = 14f
        label.setPadding(0, dp(24), 0, 0)
        col.addView(label)

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(prefs.swipeMs.toFa())
        col.addView(input)

        col.addView(button(R.string.save, 0xFF22C55E.toInt()) {
            val v = input.text.toString().toEnDigits().toIntOrNull()
            if (v != null) {
                val clamped = v.coerceIn(50, 3000)
                prefs.swipeMs = clamped
                input.setText(clamped.toFa())
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
        })

        val scroll = ScrollView(this)
        scroll.addView(col)
        return scroll
    }
}
