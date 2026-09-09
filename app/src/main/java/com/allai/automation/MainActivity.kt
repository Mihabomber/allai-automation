package com.allai.automation
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import android.view.ViewGroup.LayoutParams.*

class MainActivity : Activity() {
  private lateinit var status: TextView
  override fun onCreate(s: Bundle?) {
    super.onCreate(s)
    try {
      val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(48, 48, 48, 48)
      }
      val title = TextView(this).apply { text = "ALL AI 🤖"; textSize = 28f; gravity = Gravity.CENTER }
      status = TextView(this).apply { text = statusText(); textSize = 14f; gravity = Gravity.CENTER }
      val bOverlay = Button(this).apply { text = "1. Разрешить поверх окон" }
      val bAccess = Button(this).apply { text = "2. Включить спец. возможности" }
      val bStart = Button(this).apply { text = "3. СТАРТ (показать STOP)" }
      val bStop = Button(this).apply { text = "СТОП" }
      bOverlay.setOnClickListener {
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (e: Exception) { toast(e.message) }
      }
      bAccess.setOnClickListener {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        catch (e: Exception) { toast(e.message) }
      }
      bStart.setOnClickListener {
        if (!Settings.canDrawOverlays(this)) { toast("Сначала разреши поверх окон"); return@setOnClickListener }
        try { startService(Intent(this, FloatingStopService::class.java)); toast("STOP-кнопка показана") }
        catch (e: Exception) { toast("Ошибка: " + e.message) }
        refresh()
      }
      bStop.setOnClickListener {
        DolaAutomationService.stopRequested = true
        DiscordPoller.stopRequested = true
        try { stopService(Intent(this, FloatingStopService::class.java)) } catch (_: Exception) {}
        refresh()
      }
      root.addView(title); root.addView(status)
      root.addView(bOverlay); root.addView(bAccess); root.addView(bStart); root.addView(bStop)
      val sv = ScrollView(this).apply { addView(root) }
      setContentView(sv)
    } catch (e: Exception) { toast("onCreate: " + e.message) }
  }
  override fun onResume() { super.onResume(); try { refresh() } catch (_: Exception) {} }
  private fun statusText(): String {
    val ov = Settings.canDrawOverlays(this)
    val stop = DolaAutomationService.stopRequested
    return "Overlay: ${if (ov) "✅" else "❌"}\nБот: ${if (stop) "⏹ остановлен" else "▶ готов"}"
  }
  private fun refresh() { try { status.text = statusText() } catch (_: Exception) {} }
  private fun toast(m: String?) { try { Toast.makeText(this, m ?: "?", Toast.LENGTH_LONG).show() } catch (_: Exception) {} }
}
