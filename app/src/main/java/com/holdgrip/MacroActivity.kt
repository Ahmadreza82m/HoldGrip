package com.holdgrip

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MacroActivity : Activity() {
    private lateinit var repository: MacroRepository
    private lateinit var recoveryLog: RecoveryLog
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var runner: MacroRunner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MacroRepository(this)
        recoveryLog = RecoveryLog(this)
        buildScreen()
        renderMacros()
    }

    override fun onDestroy() {
        runner?.stop()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float = 15f, color: Int = Color.BLACK): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

    private fun actionButton(label: String, color: Int = 0xFF334155.toInt(), onClick: () -> Unit): TextView =
        text(label, 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), dp(4), dp(3), dp(4))
            layoutParams = lp
        }

    private fun buildScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(24), dp(18), dp(20))
        }
        root.addView(text("MacroClick | ساخت و اجرای ماکرو", 22f))
        root.addView(text("ماکرو یعنی چند لمس و مکث پشت‌سرهم که یک‌بار ذخیره می‌کنی و دوباره اجرا می‌کنی. توقف، مراحل بعدی را قطع می‌کند؛ لمس در حال اجرا ممکن است تا پایان زمان خودش ادامه پیدا کند.", 14f, 0xFF475569.toInt()))
        status = text("آماده", 14f, 0xFF0F766E.toInt())
        root.addView(status)
        root.addView(actionButton("＋ ساخت ماکروی جدید", 0xFF2563EB.toInt()) { createMacroDialog() })
        root.addView(actionButton("نمایش گزارش اجرا و خطاها", 0xFF475569.toInt()) { showLogs() })
        root.addView(actionButton("فعال‌سازی سرویس دسترسی", 0xFF64748B.toInt()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun createMacroDialog() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = EditText(this).apply { hint = "نام ماکرو"; setSingleLine(true) }
        val repeat = EditText(this).apply {
            hint = "تعداد تکرار (۱ تا ۹۹)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
        }
        fields.addView(name)
        fields.addView(repeat)
        AlertDialog.Builder(this)
            .setTitle("ماکروی جدید")
            .setView(fields)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ساخت") { _, _ ->
                val title = name.text.toString().trim()
                if (title.isBlank()) {
                    Toast.makeText(this, "نام ماکرو را وارد کن", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val count = repeat.text.toString().toIntOrNull()?.coerceIn(1, 99) ?: 1
                repository.save(MacroDefinition(name = title, steps = emptyList(), repeatCount = count))
                renderMacros()
            }.show()
    }

    private fun renderMacros() {
        while (root.childCount > 6) root.removeViewAt(6)
        val macros = repository.all()
        if (macros.isEmpty()) {
            root.addView(text("هنوز ماکرویی نساخته‌ای. از دکمه بالا شروع کن.", 14f, 0xFF64748B.toInt()))
            return
        }
        macros.forEach { macro ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFF1F5F9.toInt())
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), 0xFFE2E8F0.toInt())
                }
            }
            card.addView(text(macro.name, 18f))
            card.addView(text("${macro.steps.size} مرحله • ${macro.repeatCount} بار تکرار", 13f, 0xFF64748B.toInt()))
            macro.steps.forEachIndexed { index, step ->
                val description = when (step.action) {
                    MacroAction.TAP -> "لمس کوتاه در ${(step.x * 100).toInt()}٪، ${(step.y * 100).toInt()}٪"
                    MacroAction.LONG_PRESS -> "نگه‌داشتن در ${(step.x * 100).toInt()}٪، ${(step.y * 100).toInt()}٪ برای ${step.durationMs}ms"
                    MacroAction.WAIT -> "مکث ${step.durationMs}ms"
                }
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                line.addView(text("${index + 1}. $description", 13f), LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(actionButton("↑", 0xFF64748B.toInt()) { moveStep(macro, index, -1) }, LinearLayout.LayoutParams(dp(42), -2))
                line.addView(actionButton("↓", 0xFF64748B.toInt()) { moveStep(macro, index, 1) }, LinearLayout.LayoutParams(dp(42), -2))
                line.addView(actionButton("×", 0xFFB91C1C.toInt()) { removeStep(macro, index) }, LinearLayout.LayoutParams(dp(42), -2))
                card.addView(line)
            }
            val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            addRow.addView(actionButton("＋ لمس کوتاه", 0xFF0F766E.toInt()) { addStepDialog(macro, MacroAction.TAP) })
            addRow.addView(actionButton("＋ نگه‌داشتن", 0xFF0F766E.toInt()) { addStepDialog(macro, MacroAction.LONG_PRESS) })
            addRow.addView(actionButton("＋ مکث", 0xFF64748B.toInt()) { addStepDialog(macro, MacroAction.WAIT) })
            card.addView(addRow)
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(actionButton("▶ اجرا", 0xFF2563EB.toInt()) { runMacro(macro) })
            controls.addView(actionButton("■ توقف", 0xFFB91C1C.toInt()) { runner?.stop() })
            controls.addView(actionButton("حذف", 0xFF475569.toInt()) {
                AlertDialog.Builder(this@MacroActivity).setMessage("این ماکرو حذف شود؟")
                    .setNegativeButton("نه", null).setPositiveButton("حذف") { _, _ ->
                        if (runner?.isRunning == true) runner?.stop()
                        repository.delete(macro.id)
                        renderMacros()
                    }.show()
            })
            card.addView(controls)
            val outer = LinearLayout.LayoutParams(-1, -2)
            outer.topMargin = dp(12)
            root.addView(card, outer)
        }
    }

    private fun addStepDialog(macro: MacroDefinition, action: MacroAction) {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun number(hintText: String, initial: String): EditText = EditText(this).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(initial)
        }
        val x = number("موقعیت افقی (درصد، ۰ تا ۱۰۰)", "50")
        val y = number("موقعیت عمودی (درصد، ۰ تا ۱۰۰)", "50")
        val duration = number(if (action == MacroAction.WAIT) "مدت مکث (میلی‌ثانیه)" else "مدت لمس (میلی‌ثانیه)", if (action == MacroAction.LONG_PRESS) "500" else "60")
        val delay = number("مکث بعد از این مرحله (میلی‌ثانیه)", "100")
        if (action != MacroAction.WAIT) { fields.addView(x); fields.addView(y) }
        fields.addView(duration)
        if (action != MacroAction.WAIT) fields.addView(delay)
        val title = when (action) {
            MacroAction.TAP -> "افزودن لمس کوتاه"
            MacroAction.LONG_PRESS -> "افزودن نگه‌داشتن"
            MacroAction.WAIT -> "افزودن مکث"
        }
        AlertDialog.Builder(this).setTitle(title).setView(fields)
            .setNegativeButton("لغو", null)
            .setPositiveButton("افزودن") { _, _ ->
                val step = MacroStep(
                    action = action,
                    x = ((x.text.toString().toFloatOrNull() ?: 50f) / 100f).coerceIn(0f, 1f),
                    y = ((y.text.toString().toFloatOrNull() ?: 50f) / 100f).coerceIn(0f, 1f),
                    durationMs = (duration.text.toString().toLongOrNull() ?: 60L).coerceIn(1L, 5000L),
                    delayAfterMs = (delay.text.toString().toLongOrNull() ?: 100L).coerceIn(0L, 60000L)
                )
                repository.save(macro.copy(steps = macro.steps + step))
                renderMacros()
            }.show()
    }

    private fun moveStep(macro: MacroDefinition, index: Int, direction: Int) {
        val target = index + direction
        if (target !in macro.steps.indices) return
        val steps = macro.steps.toMutableList()
        val item = steps.removeAt(index)
        steps.add(target, item)
        repository.save(macro.copy(steps = steps))
        renderMacros()
    }

    private fun removeStep(macro: MacroDefinition, index: Int) {
        repository.save(macro.copy(steps = macro.steps.filterIndexed { i, _ -> i != index }))
        renderMacros()
    }

    private fun showLogs() {
        val entries = recoveryLog.recent()
        val lines = if (entries.isEmpty()) arrayOf("هنوز گزارشی ثبت نشده است") else entries.map { it.display() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("گزارش اجرا و خطاها")
            .setItems(lines, null)
            .setNeutralButton("پاک کردن گزارش") { _, _ ->
                recoveryLog.clear()
                Toast.makeText(this, "گزارش پاک شد", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun runMacro(macro: MacroDefinition) {
        if (macro.steps.isEmpty()) {
            Toast.makeText(this, "ابتدا حداقل یک مرحله اضافه کن", Toast.LENGTH_SHORT).show()
            return
        }
        val service = AccessService.instance
        if (service == null) {
            status.text = "ابتدا سرویس دسترسی را فعال کن"
            return
        }
        if (runner?.isRunning == true) runner?.stop()
        runner = MacroRunner(service) { message ->
            status.text = message
            recoveryLog.add(message)
        }
        if (!runner!!.start(macro)) status.text = "اجرای ماکرو شروع نشد"
    }
}
