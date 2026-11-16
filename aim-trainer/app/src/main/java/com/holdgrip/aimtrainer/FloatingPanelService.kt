package com.holdgrip.aimtrainer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class FloatingPanelService : Service() {
    private lateinit var windowManager: WindowManager
    private var panel: LinearLayout? = null
    private val channelId = "holdgrip_practice_panel"

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(41, buildNotification())
        showPanel()
    }

    private fun showPanel() {
        if (panel != null) return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xEE171C28.toInt())
        }
        val header = TextView(this).apply {
            text = "HOLDGRIP  •  PRACTICE"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(4, 4, 4, 10)
        }
        val open = Button(this).apply {
            text = "Open aim practice"
            setOnClickListener {
                val i = Intent(this@FloatingPanelService, AimTrainerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
        }
        val info = TextView(this).apply {
            text = "Standalone trainer only. No game input is modified."
            textSize = 11f
            setTextColor(0xFFB8C0CF.toInt())
            setPadding(4, 8, 4, 4)
        }
        val close = Button(this).apply {
            text = "Close panel"
            setOnClickListener { stopSelf() }
        }
        container.addView(header)
        container.addView(open)
        container.addView(info)
        container.addView(close)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 180
        }
        windowManager.addView(container, params)
        panel = container
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Floating practice panel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("HoldGrip practice panel")
                .setContentText("Floating panel is active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("HoldGrip practice panel")
                .setContentText("Floating panel is active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
