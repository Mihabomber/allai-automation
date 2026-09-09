package com.allai.automation

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*

// Плавающая кнопка STOP поверх всех окон. Требует SYSTEM_ALERT_WINDOW.
class FloatingStopService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var view: android.widget.Button
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        view = android.widget.Button(this).apply { text = "STOP BOT" }
        view.setOnClickListener {
            DolaAutomationService.stopRequested = true
            DiscordPoller.stopRequested = true
            stopSelf()
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        p.gravity = android.view.Gravity.TOP or android.view.Gravity.END
        wm.addView(view, p)
    }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { try { wm.removeView(view) } catch (_: Exception) {} super.onDestroy() }
}
