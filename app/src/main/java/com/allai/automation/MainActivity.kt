package com.allai.automation

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var dotOverlay: TextView
    private lateinit var dotAccess: TextView
    private lateinit var dotDiscord: TextView
    private lateinit var dotBot: TextView
    private lateinit var stepView: TextView
    private lateinit var etToken: EditText
    private lateinit var etChannel: EditText
    private lateinit var etUrl: EditText
    private lateinit var etPkg: EditText
    private lateinit var etPoll: EditText
    private lateinit var logView: TextView
    private lateinit var scrollLog: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Config.init(this)
        window.statusBarColor = 0xFF0B0F14.toInt()
        window.navigationBarColor = 0xFF0B0F14.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0B0F14.toInt(), 0xFF131A29.toInt()))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "ALL AI"; textSize = 26f; setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "  v1.8"; textSize = 12f; setTextColor(0xFF5B6B85.toInt())
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Dola automation · Discord → видео"; textSize = 12f; setTextColor(0xFF8294B0.toInt()); setPadding(0, 0, 0, dp(12))
        })

        val statusCard = card()
        val row = { v: TextView, label: String ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(6), dp(14), dp(6)) }
            r.addView(v.apply { textSize = 15f })
            r.addView(TextView(this).apply { text = " $label"; textSize = 14f; setTextColor(0xFFC9D4E3.toInt()) })
            statusCard.addView(r)
        }
        dotOverlay = TextView(this); row(dotOverlay, "Поверх окон")
        dotAccess = TextView(this); row(dotAccess, "Спец. возможности")
        dotDiscord = TextView(this); row(dotDiscord, "Discord токен/канал")
        dotBot = TextView(this); row(dotBot, "Бот")
        stepView = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF8294B0.toInt()); setPadding(dp(16), dp(2), dp(14), dp(8))
        }
        statusCard.addView(stepView)
        root.addView(statusCard, m(0, 0, 0, 12))

        val settings = card()
        etToken = input("Discord токен (Authorization)")
        etChannel = input("ID канала / ЛС")
        etUrl = input("Ссылка Dola (резерв)")
        etPkg = input("Пакет Dola (если сам не найдёт)")
        etPoll = input("Интервал опроса, сек").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        etToken.setText(Config.token); etChannel.setText(Config.channel)
        etUrl.setText(Config.dolaUrl); etPkg.setText(Config.dolaPkg); etPoll.setText(Config.pollSec.toString())
        settings.addView(etToken); settings.addView(etChannel); settings.addView(etUrl); settings.addView(etPkg); settings.addView(etPoll)
        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), 0, dp(10), 0) }
        saveRow.addView(btn("СОХРАНИТЬ", 0xFF2F6FED.toInt(), small = true).apply {
            setOnClickListener { save(); toast("Сохранено") }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, dp(8), dp(4), 0) })
        saveRow.addView(btn("ТЕСТ DISCORD", 0xFF6E44C2.toInt(), small = true).apply {
            setOnClickListener { save(); DiscordPoller.test() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(8), 0, 0) })
        settings.addView(saveRow)
        root.addView(settings, m(0, 0, 0, 12))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.addView(btn("▶ СТАРТ БОТА", 0xFF2EA043.toInt()).apply {
            setOnClickListener { onStartBot() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
        actions.addView(btn("■ СТОП", 0xFFDA3633.toInt()).apply {
            setOnClickListener { onStopBot() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) })
        root.addView(actions, m(0, 0, 0, 10))

        val perms = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        perms.addView(btn("Поверх окон", 0xFF232B3A.toInt(), small = true).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }, w(1f))
        perms.addView(btn("Спец.возможн.", 0xFF232B3A.toInt(), small = true).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, w(1f))
        perms.addView(btn("Файлы", 0xFF232B3A.toInt(), small = true).apply {
            setOnClickListener { askStorage() }
        }, w(1f))
        root.addView(perms, m(0, 0, 0, 12))

        val logCard = card().apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        val logTitle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(10), dp(14), 0); gravity = Gravity.CENTER_VERTICAL }
        logTitle.addView(TextView(this).apply { text = "Лог"; textSize = 13f; setTextColor(0xFF8294B0.toInt()) })
        logTitle.addView(btn("копировать", 0xFF232B3A.toInt(), tiny = true).apply {
            setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("allai-log", BotLog.text()))
                toast("Скопировано")
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        logCard.addView(logTitle)
        logView = TextView(this).apply {
            textSize = 10.5f
            setTextColor(0xFF9BABBF.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }
        scrollLog = ScrollView(this).apply { addView(logView) }
        logCard.addView(scrollLog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(logCard)

        setContentView(root)
    }

    override fun onStart() { super.onStart(); refresh(); handler.post(ticker) }
    override fun onStop() { super.onStop(); handler.removeCallbacks(ticker) }

    private fun onStartBot() {
        save()
        if (!Config.ready()) { toast("Заполни токен и ID канала"); return }
        if (!Settings.canDrawOverlays(this)) { toast("Разреши «поверх окон»"); return }
        if (DolaAutomationService.instance == null) { toast("Включи спец. возможности"); return }
        askStorage()
        BotEngine.start()
        try { startService(Intent(this, FloatingStopService::class.java)) } catch (_: Exception) {}
        toast("Бот запущен — жди задачу в Discord")
        refresh()
    }

    private fun onStopBot() {
        BotEngine.stop()
        DolaAutomationService.stopRequested = true
        DiscordPoller.stopRequested = true
        try { stopService(Intent(this, FloatingStopService::class.java)) } catch (_: Exception) {}
        toast("Останавливаю")
        refresh()
    }

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_VIDEO), 1)
        } else {
            val ps = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 28) ps.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (ps.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
                requestPermissions(ps.toTypedArray(), 1)
        }
    }

    private fun save() {
        val oldToken = Config.token
        val oldChannel = Config.channel
        Config.token = etToken.text.toString()
        Config.channel = etChannel.text.toString()
        Config.dolaUrl = etUrl.text.toString()
        Config.dolaPkg = etPkg.text.toString()
        Config.pollSec = etPoll.text.toString().toIntOrNull() ?: 10
        if (Config.token != oldToken || Config.channel != oldChannel) {
            Config.lastMsgId = "0"
            Config.processed = ""
        }
    }

    private fun refresh() {
        setDot(dotOverlay, Settings.canDrawOverlays(this))
        setDot(dotAccess, DolaAutomationService.instance != null)
        setDot(dotDiscord, Config.ready())
        setDot(dotBot, BotEngine.running)
        stepView.text = "Шаг: ${BotState.step}"
        val atBottom = scrollLog.childCount > 0 &&
            scrollLog.getChildAt(0).height - scrollLog.height - scrollLog.scrollY < 40
        logView.text = BotLog.text()
        if (atBottom) scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setDot(v: TextView, ok: Boolean) {
        v.text = if (ok) "●" else "○"
        v.setTextColor(if (ok) 0xFF34C77B.toInt() else 0xFF5B6B85.toInt())
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xFF151B26.toInt())
            cornerRadius = dp(16).toFloat()
        }
    }

    private fun input(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(0xFF5B6B85.toInt())
        setTextColor(0xFFE8EEF6.toInt())
        textSize = 13f
        setSingleLine(true)
        background = GradientDrawable().apply {
            setColor(0xFF0D1117.toInt())
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), 0xFF232B3A.toInt())
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(10), dp(6), dp(10), 0) }
    }

    private fun btn(text: String, color: Int, small: Boolean = false, tiny: Boolean = false): Button = Button(this).apply {
        this.text = text
        setAllCaps(false)
        textSize = when { tiny -> 11f; small -> 12.5f; else -> 15f }
        setTextColor(0xFFFFFFFF.toInt())
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(if (tiny) 8 else 14).toFloat()
        }
        setPadding(dp(12), dp(if (tiny) 4 else 10), dp(12), dp(if (tiny) 4 else 10))
        stateListAnimator = null
    }

    private fun w(weight: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
        setMargins(dp(3), 0, dp(3), 0)
    }

    private fun m(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(l, t, r, b)
    }

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
