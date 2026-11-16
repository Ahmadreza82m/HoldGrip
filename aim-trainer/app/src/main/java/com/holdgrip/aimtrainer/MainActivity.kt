package com.holdgrip.aimtrainer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(0xFF10131B.toInt())
        }
        val title = TextView(this).apply {
            text = "HOLDGRIP  /  PRACTICE"
            textSize = 23f
            setTextColor(0xFFF2F4F8.toInt())
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Floating panel: stopped"
            textSize = 14f
            setTextColor(0xFFB8C0CF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 24)
        }
        val panel = Button(this).apply {
            text = "Enable floating panel"
            setOnClickListener { enablePanel() }
        }
        val practice = Button(this).apply {
            text = "Open aim practice"
            setOnClickListener { startActivity(Intent(this@MainActivity, AimTrainerActivity::class.java)) }
        }
        val stop = Button(this).apply {
            text = "Stop floating panel"
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingPanelService::class.java))
                status.text = "Floating panel: stopped"
            }
        }
        root.addView(title)
        root.addView(status)
        root.addView(panel)
        root.addView(practice)
        root.addView(stop)
        setContentView(root)
    }

    private fun enablePanel() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            status.text = "Allow 'Display over other apps', then return and tap Enable again."
            return
        }
        val intent = Intent(this, FloatingPanelService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        status.text = "Floating panel: running"
    }
}
