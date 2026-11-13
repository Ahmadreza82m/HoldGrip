package com.holdgrip

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var profiles: ProfileStore
    private lateinit var status: TextView
    private lateinit var toggle: TextView
    private lateinit var lockToggle: TextView
    private lateinit var profileLabel: TextView
    private lateinit var sizeSeek: SeekBar
    private lateinit var alphaSeek: SeekBar
    private lateinit var durationInput: EditText
    private lateinit var curveSeek: SeekBar
    private lateinit var curveLabel: TextView

    companion object {
        private const val REQUEST_EXPORT = 7101
        private const val REQUEST_IMPORT = 7102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        profiles = ProfileStore(this, prefs)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.setText(if (AccessService.instance != null) R.string.status_on else R.string.status_off)
        toggle.setText(if (prefs.showButton) R.string.hide_button else R.string.show_button)
        lockToggle.setText(if (prefs.locked) R.string.unlock_button else R.string.lock_button)
        profileLabel.text = "پروفایل فعال: ${profiles.active()?.name ?: "—"}"
        if (::sizeSeek.isInitialized) sizeSeek.progress = (prefs.buttonSizeDp - 32).coerceIn(0, 56)
        if (::alphaSeek.isInitialized) alphaSeek.progress = ((prefs.buttonAlpha - 0.2f) * 100f).toInt().coerceIn(0, 80)
        if (::durationInput.isInitialized && !durationInput.hasFocus()) durationInput.setText(prefs.swipeMs.toFa())
        if (::curveSeek.isInitialized) curveSeek.progress = (prefs.curve * 100f).toInt().coerceIn(-100, 100) + 100
        updateCurveLabel()
    }

    private fun updateCurveLabel() {
        if (!::curveLabel.isInitialized) return
        val value = (prefs.curve * 100f).toInt()
        curveLabel.text = when {
            value == 0 -> "مسیر صاف"
            value > 0 -> "خمیدگی سمت مثبت: +$value"
            else -> "خمیدگی سمت مخالف: $value"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun button(textRes: Int, color: Int, onClick: () -> Unit): TextView =
        buttonText(getString(textRes), color, onClick)

    private fun buttonText(text: String, color: Int, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.WHITE)
        t.textSize = 15f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(16), dp(13), dp(16), dp(13))
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(14).toFloat()
        t.background = bg
        t.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(9)
        t.layoutParams = lp
        return t
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(0xFF111827.toInt())
        setPadding(0, dp(22), 0, dp(4))
    }

    private fun showNameDialog(title: String, initial: String = "", onSave: (String) -> Unit) {
        val field = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(initial)
            setSelection(text.length)
            hint = "مثلاً تنظیم هدشات"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(field)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isNotEmpty()) onSave(value)
                else Toast.makeText(this, "نام نمی‌تواند خالی باشد", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun chooseProfile() {
        val items = profiles.all()
        val activeId = profiles.active()?.id
        AlertDialog.Builder(this)
            .setTitle("انتخاب پروفایل ژست")
            .setItems(items.map { if (it.id == activeId) "✓  ${it.name}" else it.name }.toTypedArray()) { _, which ->
                profiles.saveCurrent()
                profiles.switchTo(items[which].id)
                recreate()
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun createProfile() {
        showNameDialog("ساخت پروفایل جدید") { name ->
            profiles.create(name)
            recreate()
        }
    }

    private fun renameProfile() {
        val current = profiles.active() ?: return
        showNameDialog("تغییر نام پروفایل", current.name) { name ->
            profiles.renameActive(name)
            refresh()
        }
    }

    private fun deleteProfile() {
        if (profiles.all().size <= 1) {
            Toast.makeText(this, "حداقل یک پروفایل باید باقی بماند", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("حذف پروفایل فعال؟")
            .setMessage("پروفایل «${profiles.active()?.name}» حذف می‌شود. این کار قابل بازگشت نیست.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("حذف") { _, _ ->
                profiles.deleteActive()
                recreate()
            }
            .show()
    }

    private fun exportBackup() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "HoldGrip-backup.hgp")
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun importBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    @Deprecated("Handled for Android document picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(profiles.exportBackup().toByteArray(Charsets.UTF_8))
                    } ?: error("فایل برای نوشتن باز نشد")
                    Toast.makeText(this, "نسخه پشتیبان ذخیره شد", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "ذخیره ناموفق بود: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_IMPORT -> {
                try {
                    val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("فایل برای خواندن باز نشد")
                    if (profiles.importBackup(raw)) {
                        AccessService.instance?.let {
                            if (prefs.showButton) it.showButton() else it.hideButton()
                        }
                        Toast.makeText(this, "پشتیبان بازیابی شد", Toast.LENGTH_LONG).show()
                        recreate()
                    } else {
                        Toast.makeText(this, "فایل پشتیبان معتبر نیست", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "بازیابی ناموفق بود: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(34), dp(22), dp(28))
        col.layoutDirection = View.LAYOUT_DIRECTION_RTL
        col.setBackgroundColor(0xFFF5F6F8.toInt())

        val title = TextView(this).apply {
            setText(R.string.title)
            textSize = 25f
            setTextColor(0xFF111827.toInt())
        }
        col.addView(title)

        val usage = TextView(this).apply {
            setText(R.string.usage)
            textSize = 14f
            setTextColor(0xFF4B5563.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(usage)

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF1F2937.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(status)

        col.addView(sectionTitle("پروفایل‌های ژست"))
        profileLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            setPadding(0, dp(4), 0, dp(2))
        }
        col.addView(profileLabel)
        col.addView(buttonText("انتخاب پروفایل", 0xFF374151.toInt()) { chooseProfile() })
        col.addView(buttonText("ساخت پروفایل از تنظیمات فعلی", 0xFF4F46E5.toInt()) { createProfile() })
        col.addView(buttonText("تغییر نام پروفایل فعال", 0xFF64748B.toInt()) { renameProfile() })
        col.addView(buttonText("حذف پروفایل فعال", 0xFFB91C1C.toInt()) { deleteProfile() })

        col.addView(sectionTitle("کنترل و دسترسی"))
        col.addView(button(R.string.enable_accessibility, 0xFF6B7280.toInt()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button(R.string.edit_points, 0xFF8B5CF6.toInt()) {
            val service = AccessService.instance
            if (service == null) Toast.makeText(this, R.string.need_service, Toast.LENGTH_LONG).show()
            else {
                moveTaskToBack(true)
                service.showEditor()
            }
        })

        toggle = button(R.string.hide_button, 0xFF6B7280.toInt()) {
            prefs.showButton = !prefs.showButton
            if (prefs.showButton) AccessService.instance?.showButton() else AccessService.instance?.hideButton()
            refresh()
        }
        col.addView(toggle)

        lockToggle = button(R.string.unlock_button, 0xFF374151.toInt()) {
            prefs.locked = !prefs.locked
            refresh()
        }
        col.addView(lockToggle)

        col.addView(sectionTitle("ظاهر دکمه شناور"))
        col.addView(TextView(this).apply {
            setText(R.string.button_size)
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            setPadding(0, dp(8), 0, dp(4))
        })
        sizeSeek = SeekBar(this).apply {
            max = 56
            progress = (prefs.buttonSizeDp - 32).coerceIn(0, 56)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.buttonSizeDp = progress + 32
                    AccessService.instance?.refreshButtonAppearance()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        col.addView(sizeSeek)

        col.addView(TextView(this).apply {
            setText(R.string.button_opacity)
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            setPadding(0, dp(10), 0, dp(4))
        })
        alphaSeek = SeekBar(this).apply {
            max = 80
            progress = ((prefs.buttonAlpha - 0.2f) * 100f).toInt().coerceIn(0, 80)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.buttonAlpha = 0.2f + progress / 100f
                    AccessService.instance?.refreshButtonAppearance()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        col.addView(alphaSeek)

        col.addView(sectionTitle("تنظیم حرکت"))
        col.addView(TextView(this).apply {
            text = "میزان و جهت قوس مسیر"
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            setPadding(0, dp(8), 0, dp(4))
        })
        curveLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF4F46E5.toInt())
            setPadding(0, dp(2), 0, dp(2))
        }
        col.addView(curveLabel)
        curveSeek = SeekBar(this).apply {
            max = 200
            progress = (prefs.curve * 100f).toInt().coerceIn(-100, 100) + 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.curve = ((progress - 100) / 100f).coerceIn(-1f, 1f)
                    updateCurveLabel()
                    profiles.saveCurrent()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        col.addView(curveSeek)
        col.addView(TextView(this).apply {
            text = "وسط نوار = مسیر صاف؛ هرچه از وسط دورتر شوی، قوس بیشتر می‌شود. با عبور از وسط، جهت قوس عوض می‌شود."
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, dp(2), 0, dp(8))
        })
        col.addView(TextView(this).apply {
            setText(R.string.swipe_duration)
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            setPadding(0, dp(8), 0, dp(4))
        })
        durationInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.swipeMs.toFa())
            textSize = 16f
        }
        col.addView(durationInput)

        col.addView(button(R.string.save, 0xFF16A34A.toInt()) {
            val value = durationInput.text.toString().toEnDigits().toIntOrNull()
            if (value == null) {
                Toast.makeText(this, "یک عدد معتبر وارد کن", Toast.LENGTH_SHORT).show()
            } else {
                prefs.swipeMs = value.coerceIn(50, 3000)
                profiles.saveCurrent()
                durationInput.setText(prefs.swipeMs.toFa())
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
        })

        col.addView(sectionTitle("پشتیبان‌گیری و انتقال"))
        col.addView(buttonText("خروجی گرفتن از همه پروفایل‌ها و تنظیمات", 0xFF0F766E.toInt()) { exportBackup() })
        col.addView(buttonText("بازیابی پشتیبان از فایل", 0xFF0F766E.toInt()) {
            AlertDialog.Builder(this)
                .setTitle("بازیابی پشتیبان")
                .setMessage("تنظیمات و پروفایل‌های فعلی با اطلاعات فایل پشتیبان جایگزین می‌شوند. قبل از ادامه، از تنظیمات فعلی خروجی بگیر.")
                .setNegativeButton("انصراف", null)
                .setPositiveButton("انتخاب فایل") { _, _ -> importBackup() }
                .show()
        })

        val scroll = ScrollView(this)
        scroll.addView(col)
        return scroll
    }
}
