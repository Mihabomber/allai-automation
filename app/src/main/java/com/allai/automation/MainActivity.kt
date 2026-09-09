package com.allai.automation
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
class MainActivity : Activity() {
  override fun onCreate(s: Bundle?) {
    super.onCreate(s)
    val b = Button(this).apply { text = "Старт: overlay + доступность" }
    b.setOnClickListener {
      if (!Settings.canDrawOverlays(this))
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
      else startService(Intent(this, FloatingStopService::class.java))
      startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    setContentView(b)
  }
}
