package com.allai.automation

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.Button
import android.widget.Toast

class FloatingStopService : Service() {
    private var wm: WindowManager? = null
    private var view: Button? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showFloat()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        showFloat()
    }

    private fun showFloat() {
        if (view != null) return
        try {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Нет разрешения поверх окон", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            view = Button(this).apply {
                text = "■ STOP BOT"
                setTextColor(0xFFFFFFFF.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFDA3633.toInt())
                    cornerRadius = 20 * resources.displayMetrics.density
                }
                setPadding(32, 18, 32, 18)
            }
            view!!.setOnClickListener {
                DolaAutomationService.stopRequested = true
                DiscordPoller.stopRequested = true
                Toast.makeText(this, "Бот остановлен", Toast.LENGTH_SHORT).show()
                removeFloat()
                stopSelf()
            }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT)
            p.gravity = Gravity.TOP or Gravity.END
            p.x = 12; p.y = 110
            wm!!.addView(view, p)
        } catch (e: Exception) {
            try { Toast.makeText(this, "Float: " + e.message, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            removeFloat()
            stopSelf()
        }
    }

    private fun removeFloat() {
        try { if (view != null) wm?.removeView(view) } catch (_: Exception) {}
        view = null
    }

    override fun onDestroy() { removeFloat(); super.onDestroy() }
}
