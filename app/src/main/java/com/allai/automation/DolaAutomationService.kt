package com.allai.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DolaAutomationService : AccessibilityService() {

    companion object {
        @Volatile var instance: DolaAutomationService? = null
        @Volatile var stopRequested = false
        @Volatile var lastVideoUrl = ""
        private const val LOGIN_BTNS = "Войти\u0000Войдите\u0000Вход\u0000Sign In\u0000Sign in\u0000Log in"
        private const val GOOGLE_BTNS = "Продолжить с Google\u0000Продолжить через Google\u0000Войти через Google\u0000Sign in with Google\u0000Continue with Google"
        private const val DOWNLOAD_BTNS = "Скачать\u0000Download\u0000Save\u0000Сохранить"
        private const val READY = 0
        private const val LIMIT = 1
        private const val TIMEOUT = 2
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val dolaPkgGuesses = listOf("com.larus.wolf", "com.dola.ai", "ai.dola.app", "com.dola.android", "com.dolai.app", "app.dola")
    @Volatile private var pkgListLogged = false
    private val watchWords = listOf("Смотреть видео", "Watch video")
    private val limitWords = listOf("лимит", "превышен", "подписк", "апгрейд", "достигнут", "попробуйте завтра", "попробуй позже", "limit reached", "upgrade")

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BotLog.add("Спец. возможности подключены")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun runJob(job: DiscordPoller.Job, done: (Boolean, List<File>, String) -> Unit) {
        thread(name = "allai-job") {
            var files = listOf<File>()
            var err = ""
            try {
                files = execute(job)
            } catch (t: Throwable) {
                err = t.message ?: "сбой"
                BotLog.add("Ошибка: $err")
            }
            done(err.isEmpty(), files, err)
        }
    }

    private fun execute(job: DiscordPoller.Job): List<File> {
        ensureDolaApp()
        Thread.sleep(3000)
        handleLogin()
        handleBirthday()
        waitChatReady(30_000, retryLogin = false)
        val prep = prepare(job)
        val out = ArrayList<File>()
        BotLog.add("Генерирую PART1")
        out.add(generateFlow(job.part1, "part1", prep))
        if (job.part2.isNotEmpty()) {
            checkStop()
            BotLog.add("Генерирую PART2")
            out.add(generateFlow(job.part2, "part2", prep))
        }
        BotLog.add("Задача выполнена: ${out.size} видео")
        return out
    }

    private fun generateFlow(prompt: String, tag: String, prep: Prep): File {
        var attempts = 0
        while (attempts < 2) {
            attempts++
            checkStop()
            sendViaPanel(prompt, prep)
            BotLog.add("Жду генерацию ($tag)...")
            when (waitReady(20 * 60_000)) {
                READY -> {
                    val f = watchAndDownload(tag)
                    if (f == null && lastVideoUrl.isEmpty()) fail("Видео не скачалось")
                    if (f != null && f.length() > 10_000) {
                        BotLog.add("$tag готов: ${f.length() / 1024} KB")
                        return f
                    }
                    if (lastVideoUrl.isNotEmpty()) {
                        BotLog.add("$tag ссылка: $lastVideoUrl")
                        val dummy = File(getExternalFilesDir("jobs"), "allai_$tag.link.txt")
                        dummy.writeText(lastVideoUrl)
                        return dummy
                    }
                    fail("Видео не скачалось")
                }
                else -> {
                    BotLog.add("Лимит/сбой Dola — пересоздаю аккаунт (попытка $attempts)")
                    rotateAccount()
                }
            }
        }
        fail("Не удалось сгенерировать $tag")
    }

    private fun sendViaPanel(prompt: String, prep: Prep) {
        bringDolaToFront()
        handleLogin()
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("allai", prompt))
        switchToPro()
        openVideoPanel()
        if (!typePrompt(prompt)) fail("Не нашёл поле ввода")
        prep.imgs.forEachIndexed { i, f ->
            BotLog.add("Креплю фото ${i + 1}")
            attachPicker(f.name, listOf("Справочный", "Reference", "Upload", "Upload image", "Choose image", "Photo"))
        }
        prep.audio?.let {
            BotLog.add("Креплю аудио")
            attachPicker(it.name, listOf("Аудио", "Audio", "Choose audio"))
        }
        if (!sendPrompt()) fail("Не нашёл кнопку отправки")
        Thread.sleep(4000)
        checkStop()
    }

    private fun ensureDolaApp() {
        launchDola()
        Thread.sleep(8000)
        checkStop()
    }

    private fun bringDolaToFront() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: ""
        if (Config.dolaPkg.isNotEmpty() && pkg == Config.dolaPkg) return
        launchDola()
        Thread.sleep(2500)
        checkStop()
    }

    private fun launchDola() {
        val pkg = findDolaPkg()
        val intent = if (pkg != null) packageManager.getLaunchIntentForPackage(pkg) else null
        if (intent != null) {
            BotLog.add("Открываю приложение Dola ($pkg)")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        } else {
            BotLog.add("Приложение Dola не найдено — открываю ссылку")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.dolaUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun findDolaPkg(): String? {
        if (Config.dolaPkg.isNotEmpty() && packageManager.getLaunchIntentForPackage(Config.dolaPkg) != null) return Config.dolaPkg
        var hit: String? = null
        try {
            for (info in packageManager.getInstalledPackages(0)) {
                val name = info.packageName ?: continue
                if (name == packageName) continue
                val label = try { packageManager.getApplicationLabel(info.applicationInfo).toString() } catch (e: Exception) { "" }
                if (label.contains("dola", true) || name.contains("dola", true)) { hit = name; break }
            }
        } catch (e: Exception) {}
        if (hit == null) {
            try {
                val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                for (ri in packageManager.queryIntentActivities(main, 0)) {
                    val p = ri.activityInfo?.packageName ?: continue
                    if (p == packageName) continue
                    val label = try { ri.loadLabel(packageManager).toString() } catch (e: Exception) { "" }
                    if (label.contains("dola", true) || p.contains("dola", true)) { hit = p; break }
                }
            } catch (e: Exception) {}
        }
        if (hit == null) {
            for (g in dolaPkgGuesses) {
                try {
                    if (packageManager.getLaunchIntentForPackage(g) != null) { hit = g; break }
                } catch (e: Exception) {}
            }
        }
        if (hit != null) {
            Config.dolaPkg = hit
            BotLog.add("Найдено приложение Dola: $hit")
        } else if (!pkgListLogged) {
            pkgListLogged = true
            BotLog.add("Dola не найден! Приложения на телефоне (укажи пакет Dola в поле «Пакет Dola»):")
            try {
                val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                packageManager.queryIntentActivities(main, 0).take(25).forEach {
                    val label = try { it.loadLabel(packageManager).toString() } catch (e: Exception) { "?" }
                    BotLog.add("• $label | ${it.activityInfo?.packageName ?: "?"}")
                }
            } catch (e: Exception) {}
        }
        return hit
    }

    private fun waitChatReady(timeout: Long, retryLogin: Boolean = true) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            checkStop()
            if (birthdayVisible()) {
                handleBirthday()
                continue
            }
            if (loginVisible()) {
                handleLogin()
                continue
            }
            if (findEditable() != null) return
            if (screenHasText(listOf("Сообщение", "Опишите видео", "Создано ИИ", "Fast"))) return
            Thread.sleep(1500)
        }
        BotLog.add("Окно Dola не подтвердилось, продолжаю")
    }

    private fun handleLogin() {
        if (!loginVisible()) return
        BotLog.add("Вижу экран входа — жму «Продолжить с Google»")
        if (tapByText(GOOGLE_BTNS.split("\u0000"), 12_000, optional = true)) {
            BotLog.add("Нажал «Продолжить с Google»")
        } else if (ocrTapElement("Продолжить с Google|Continue with Google")) {
            BotLog.add("Нажал «Продолжить с Google» (OCR)")
        } else {
            val scr = Rect()
            rootInActiveWindow?.getBoundsInScreen(scr)
            if (scr.height() > 0) {
                BotLog.add("Жму кнопку Google по координатам")
                tap(scr.left + scr.width() * 0.5f, scr.top + scr.height() * 0.58f)
            }
        }
        Thread.sleep(2500)
        pickGoogleAccount(25_000)
        Thread.sleep(2000)
        tapExact(listOf("Продолжить", "Далее", "Согласен", "I agree"), 5_000, optional = true)
        BotLog.add("Вход: шаг Google пройден")
    }

    private fun birthdayVisible(): Boolean {
        val keys = listOf("день рождения", "дата рождения", "Когда у вас", "Ваш возраст", "date of birth", "Date of Birth")
        if (findNodes { matches(it, keys, false) }.isNotEmpty()) return true
        return screenHasText(listOf("день рождения", "дата рождения", "Когда у вас", "Ваш возраст", "date of birth"))
    }

    private fun handleBirthday() {
        if (!birthdayVisible()) {
            Thread.sleep(1200)
            if (!birthdayVisible()) return
        }
        BotLog.add("Экран дня рождения — кручу ЛЕВОЕ колесо года до 2000")
        if (!scrollYearTo2000()) BotLog.add("2000 не найден — продолжаю")
        Thread.sleep(800)
        if (!tapExact(listOf("Далее"), 6_000, optional = true) && !ocrTapElement("Далее")) {
            val scr = Rect()
            rootInActiveWindow?.getBoundsInScreen(scr)
            if (scr.height() > 0) {
                BotLog.add("Жму Далее по координатам")
                tap(scr.left + scr.width() * 0.50f, scr.top + scr.height() * 0.93f)
            }
        }
        Thread.sleep(2000)
        BotLog.add("Дату рождения прошёл")
    }

    private fun scrollYearTo2000(): Boolean {
        val scr = Rect()
        rootInActiveWindow?.getBoundsInScreen(scr) ?: return false
        if (scr.height() == 0) return false
        val xYear = scr.left + scr.width() * 0.18f
        val yFrom = scr.top + scr.height() * 0.36f
        val yTo = scr.top + scr.height() * 0.52f
        var i = 0
        while (i < 40) {
            checkStop()
            val hit = findNodes { (it.text ?: "").toString().trim() == "2000" }
            if (hit.isNotEmpty()) {
                val b = Rect(); hit[0].getBoundsInScreen(b)
                tap(b.exactCenterX(), b.exactCenterY())
                BotLog.add("Год 2000 выбран")
                return true
            }
            if (ocrTapElement("\\b2000\\b")) {
                BotLog.add("Год 2000 выбран (OCR)")
                return true
            }
            swipe(xYear, yFrom, xYear, yTo, 220)
            Thread.sleep(280)
            i++
        }
        return false
    }

    private fun loginVisible(): Boolean {
        val keys = listOf("Продолжить с Google", "Continue with Google", "Войдите в аккаунт", "Продолжить на телефоне")
        if (findNodes { matches(it, keys, false) }.isNotEmpty()) return true
        return screenHasText(listOf("Продолжить с Google", "Войдите в аккаунт", "Continue with Google"))
    }

    private fun pickGoogleAccount(timeout: Long) {
        BotLog.add("Жду выбор аккаунта Google…")
        val end = System.currentTimeMillis() + timeout
        var tappedCoord = false
        while (System.currentTimeMillis() < end) {
            checkStop()
            val acc = findNodes {
                val t = (it.text ?: "").toString() + " " + (it.contentDescription ?: "")
                t.contains("@gmail", true) || t.contains("googlemail", true)
            }
            if (acc.isNotEmpty()) {
                val top = acc.minByOrNull { n ->
                    val b = Rect()
                    n.getBoundsInScreen(b)
                    b.top
                } ?: acc[0]
                clickNode(top)
                BotLog.add("Выбран верхний аккаунт Google")
                Thread.sleep(3000)
                return
            }
            if (ocrTapElement("@gmail")) {
                BotLog.add("Выбран аккаунт Google (OCR)")
                Thread.sleep(3000)
                return
            }
            if (ocrTapElement("Выберите аккаунт")) {
                Thread.sleep(400)
            }
            if (!tappedCoord && System.currentTimeMillis() > end - timeout + 4_000) {
                tappedCoord = true
                val scr = Rect()
                rootInActiveWindow?.getBoundsInScreen(scr)
                if (scr.height() > 0) {
                    BotLog.add("Жму первый аккаунт по координатам")
                    tap(scr.left + scr.width() * 0.50f, scr.top + scr.height() * 0.52f)
                    Thread.sleep(800)
                    tap(scr.left + scr.width() * 0.50f, scr.top + scr.height() * 0.58f)
                }
            }
            Thread.sleep(800)
        }
        BotLog.add("Выбор аккаунта не подтверждён, продолжаю")
    }

    private fun switchToPro() {
        BotLog.add("Включаю Pro")
        if (proChipOn()) {
            BotLog.add("Pro уже включён")
            return
        }
        val fast = tapByText(listOf("Fast", "Быстрый"), 5_000, optional = true) || ocrTapElement("Fast|Быстрый")
        if (!fast) {
            val scr = Rect(); rootInActiveWindow?.getBoundsInScreen(scr)
            if (scr.height() > 0) {
                BotLog.add("Жму Fast по координатам")
                tap(scr.left + scr.width() * 0.16f, scr.top + scr.height() * 0.46f)
            }
        }
        Thread.sleep(1500)
        val pro = tapByText(listOf("Продвинутая модель"), 6_000, optional = true) ||
            ocrTapElement("Продвинутая модель|Продвинутая") ||
            tapExact(listOf("Pro"), 3_000, optional = true)
        if (pro) BotLog.add("Выбрал Pro") else BotLog.add("Пункт Pro не найден")
        Thread.sleep(1200)
    }

    private fun proChipOn(): Boolean {
        val nodes = findNodes { n ->
            val t = (n.text ?: "").toString().trim()
            val d = (n.contentDescription ?: "").toString().trim()
            t.startsWith("Pro") || d.startsWith("Pro") || t.equals("Pro", true) || d.equals("Pro", true)
        }
        return nodes.isNotEmpty()
    }

    private fun openVideoPanel() {
        BotLog.add("Открываю «Создание контента»")
        val ok = tapByText(listOf("Создание контента", "Создание кон"), 6_000, optional = true) ||
            ocrTapElement("Создание контента|Создание кон")
        if (!ok) {
            val scr = Rect(); rootInActiveWindow?.getBoundsInScreen(scr)
            if (scr.height() > 0) {
                BotLog.add("Жму «Создание контента» по координатам")
                tap(scr.left + scr.width() * 0.82f, scr.top + scr.height() * 0.46f)
            }
        }
        Thread.sleep(1500)
        val vid = tapExact(listOf("Видео"), 5_000, optional = true) || ocrTapElement("Видео")
        if (vid) BotLog.add("Вкладка Видео") else BotLog.add("Вкладка Видео не найдена")
        Thread.sleep(1000)
    }

    private fun waitReady(timeout: Long): Int {
        val end = System.currentTimeMillis() + timeout
        var lastLog = 0L
        while (System.currentTimeMillis() < end) {
            checkStop()
            if (screenHasText(watchWords)) return READY
            if (screenHasText(limitWords)) return LIMIT
            if (System.currentTimeMillis() - lastLog > 60_000) {
                BotLog.add("Всё ещё генерируется...")
                lastLog = System.currentTimeMillis()
            }
            Thread.sleep(4000)
        }
        return TIMEOUT
    }

    private fun watchAndDownload(tag: String): File? {
        lastVideoUrl = ""
        val before = System.currentTimeMillis() / 1000 - 5
        BotLog.add("Жму синюю ссылку «Смотреть видео»")
        if (!tapByText(watchWords, 12_000, optional = true) && !ocrTapElement("Смотреть видео|Watch video")) {
            val blues = findNodes { n ->
                val t = (n.text ?: "").toString()
                n.isClickable && (t.contains("http", true) || t.contains("dola.com", true) || t.contains("смотр", true))
            }
            if (blues.isNotEmpty()) clickNode(smallestNode(blues))
        }
        Thread.sleep(4000)
        checkStop()
        val scr0 = Rect(); rootInActiveWindow?.getBoundsInScreen(scr0)
        if (scr0.height() > 0) {
            BotLog.add("Жму на видео")
            tap(scr0.exactCenterX(), scr0.top + scr0.height() * 0.42f)
            Thread.sleep(1200)
            BotLog.add("Жму три точки")
            tap(scr0.left + scr0.width() * 0.90f, scr0.top + scr0.height() * 0.72f)
            Thread.sleep(1200)
        }
        if (!tapByText(DOWNLOAD_BTNS.split("\u0000"), 8_000, optional = true)) {
            ocrTapElement("Скачать|Download")
        }
        Thread.sleep(2500)
        grabChromeUrl()
        val end = System.currentTimeMillis() + 5 * 60_000
        while (System.currentTimeMillis() < end) {
            checkStop()
            tryDownloadInBrowser()
            grabChromeUrl()
            val dst = File(getExternalFilesDir("jobs"), "allai_$tag.mp4")
            val f = waitVideo(before, 15_000, dst)
            if (f != null) {
                grabChromeUrl()
                backToDola()
                return f
            }
            if (lastVideoUrl.isNotEmpty()) {
                BotLog.add("Ссылка скопирована: $lastVideoUrl")
                backToDola()
                return File(getExternalFilesDir("jobs"), "allai_$tag.mp4").also { it.writeText(lastVideoUrl) }
            }
            Thread.sleep(2000)
        }
        return null
    }

    private fun grabChromeUrl() {
        val nodes = findNodes { n ->
            val t = (n.text ?: "").toString() + " " + (n.contentDescription ?: "")
            t.contains("dola.com", true) || t.contains("http://", true) || t.contains("https://", true)
        }
        for (n in nodes) {
            val t = (n.text ?: "").toString().trim()
            val d = (n.contentDescription ?: "").toString().trim()
            val u = when {
                t.contains("dola.com") || t.startsWith("http") -> t
                d.contains("dola.com") || d.startsWith("http") -> d
                else -> ""
            }
            if (u.contains("dola.com") || u.startsWith("http")) {
                setVideoUrl(u.split(" ").first())
                return
            }
        }
        val ocr = ocrText() ?: return
        val re = Regex("(https?://\\S*dola[^\\s]*|v\\d+-dola[^\\s]*|\\d{2,}-dola\\.dola\\.com[^\\s]*)", RegexOption.IGNORE_CASE)
        val hit = re.find(ocr.replace("\n", " "))?.value
        if (hit != null) setVideoUrl(if (hit.startsWith("http")) hit else "https://$hit")
    }

    private fun setVideoUrl(u: String) {
        if (u.isNotEmpty() && u != lastVideoUrl) {
            lastVideoUrl = u
            BotLog.add("URL: $lastVideoUrl")
        }
    }

    private fun tryDownloadInBrowser() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        val web = findNodes { it.className?.toString().equals("android.webkit.WebView", true) }.firstOrNull()
        val isBrowser = pkg.contains("chrome", true) || pkg.contains("browser", true) || web != null
        if (!isBrowser) return
        grabChromeUrl()
        if (tapByText(DOWNLOAD_BTNS.split("\u0000"), 2_000, optional = true)) {
            Thread.sleep(2500)
            return
        }
        val b = Rect()
        if (web != null) web.getBoundsInScreen(b) else {
            root.getBoundsInScreen(b)
            b.top += (b.height() * 0.10).toInt()
        }
        tap(b.exactCenterX(), b.exactCenterY())
        Thread.sleep(800)
        tap(b.left + b.width() * 0.90f, b.bottom - b.height() * 0.11f)
        Thread.sleep(1200)
        if (tapByText(DOWNLOAD_BTNS.split("\u0000"), 5_000, optional = true) || ocrTapElement("Скачать")) Thread.sleep(2500)
        grabChromeUrl()
    }

    private fun backToDola() {
        val pkg = if (Config.dolaPkg.isNotEmpty()) Config.dolaPkg else findDolaPkg()
        val i = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(i)
            Thread.sleep(2500)
        } else {
            goBack()
            goBack()
        }
    }

    private fun goBack() {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Exception) {}
        Thread.sleep(1200)
    }

    private fun rotateAccount() {
        BotLog.add("Ротация: удаляю аккаунт Dola")
        bringDolaToFront()
        Thread.sleep(2000)
        val scr = Rect()
        rootInActiveWindow?.getBoundsInScreen(scr)
        BotLog.add("Жму меню ≡")
        if (!tapDescOrText(listOf("Меню", "Menu", "Открыть меню", "navigation", "drawer"))) {
            tap(scr.left + scr.width() * 0.065f, scr.top + scr.height() * 0.072f)
        }
        Thread.sleep(1800)
        BotLog.add("Жму настройки")
        if (!tapDescOrText(listOf("Настройки", "Settings", "gear"))) {
            rootInActiveWindow?.getBoundsInScreen(scr)
            tap(scr.left + scr.width() * 0.72f, scr.top + scr.height() * 0.93f)
        }
        Thread.sleep(1800)
        if (!tapByText(listOf("Аккаунт Dola"), 8_000, optional = true) && !ocrTapElement("Аккаунт Dola")) {
            BotLog.add("Жму Аккаунт Dola по координатам")
            rootInActiveWindow?.getBoundsInScreen(scr)
            tap(scr.exactCenterX(), scr.top + scr.height() * 0.22f)
        }
        Thread.sleep(1500)
        val delWords = listOf("Удалить учетную запись", "Удалить учётную запись")
        var gotDel = tapByText(delWords, 4_000, optional = true) || ocrTapElement("Удалить учетную|Удалить учётную")
        var sw = 0
        while (!gotDel && sw < 4) {
            sw++
            checkStop()
            BotLog.add("Не нашёл удаление — скроллю вниз ($sw)")
            rootInActiveWindow?.getBoundsInScreen(scr)
            swipe(scr.exactCenterX(), scr.top + scr.height() * 0.7f, scr.exactCenterX(), scr.top + scr.height() * 0.3f, 400)
            Thread.sleep(800)
            gotDel = tapByText(delWords, 3_000, optional = true) || ocrTapElement("Удалить учетную|Удалить учётную")
        }
        if (!gotDel) fail("Не нашёл «Удалить учетную запись»")
        Thread.sleep(1200)
        tapExact(listOf("Удалить"), 8_000, optional = true) || ocrTapElement("^Удалить$")
        Thread.sleep(1500)
        var gotNow = tapByText(listOf("Удалить сейчас"), 8_000, optional = true) || ocrTapElement("Удалить сейчас")
        if (!gotNow) {
            BotLog.add("Жму «Удалить сейчас» по координатам")
            rootInActiveWindow?.getBoundsInScreen(scr)
            tap(scr.exactCenterX(), scr.top + scr.height() * 0.55f)
            Thread.sleep(1500)
            gotNow = tapByText(listOf("Удалить сейчас"), 5_000, optional = true) || ocrTapElement("Удалить сейчас")
        }
        if (!gotNow) fail("Не нашёл «Удалить сейчас»")
        BotLog.add("Аккаунт удалён — жду пересоздания")
        Thread.sleep(6000)
        tapExact(listOf("Продолжить", "Начать"), 5_000, optional = true)
        handleLogin()
        handleBirthday()
        waitChatReady(30_000, retryLogin = false)
        BotLog.add("Новый аккаунт готов")
    }

    private fun checkStop() {
        if (stopRequested) throw RuntimeException("Остановлено пользователем")
    }

    private class Prep(val imgs: List<File>, val audio: File?)

    private fun prepare(job: DiscordPoller.Job): Prep {
        val dir = getExternalFilesDir("jobs")!!
        dir.mkdirs()
        val imgs = ArrayList<File>()
        job.images.forEachIndexed { i, u ->
            val f = File(dir, "allai_img${i + 1}.png")
            if (DiscordPoller.download(u, f)) imgs.add(f) else BotLog.add("Фото ${i + 1}: не скачалось")
        }
        var audio: File? = null
        job.audio?.let { u ->
            val raw = File(dir, "audio_raw")
            if (DiscordPoller.download(u, raw)) {
                val dst = File(dir, "allai_audio.wav")
                audio = try {
                    AudioConvert.prepare(raw, dst)
                } catch (e: Exception) {
                    BotLog.add("Аудио: ${e.message}")
                    null
                }
            } else BotLog.add("Аудио: не скачалось")
        }
        imgs.forEach { publishToDownloads(it, "image/png") }
        audio?.let { publishToDownloads(it, "audio/wav") }
        return Prep(imgs, audio)
    }

    private fun publishToDownloads(src: File, mime: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                contentResolver.openOutputStream(uri)?.use { o ->
                    src.inputStream().use { it.copyTo(o, 65536) }
                }
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                val dst = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), src.name)
                src.copyTo(dst, overwrite = true)
            }
        } catch (e: Exception) {
            BotLog.add("publish: ${e.message}")
        }
    }

    private fun attachPicker(fileName: String, btns: List<String>) {
        if (!tapByText(btns, 8_000, optional = true)) {
            BotLog.add("Кнопка вложения не найдена — прикрепи $fileName вручную (40с)")
            repeat(40) { checkStop(); Thread.sleep(1000) }
            return
        }
        Thread.sleep(2500)
        tapByText(listOf("Downloads", "Загрузки", "Recent", "Недавние"), 3_000, optional = true)
        Thread.sleep(1000)
        if (!pickerTap(fileName, 20_000)) BotLog.add("$fileName не найден в списке — выбери вручную")
        Thread.sleep(1500)
        tapByText(listOf("Open", "Открыть", "Done", "Готово", "Select", "Выбрать"), 4_000, optional = true)
        Thread.sleep(1500)
    }

    private fun pickerTap(fileName: String, timeout: Long): Boolean {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            checkStop()
            val nodes = findNodes {
                (it.text ?: "").toString().contains(fileName, true) ||
                    (it.contentDescription ?: "").toString().contains(fileName, true)
            }
            for (n in nodes) {
                if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val p = clickableParent(n)
                if (p != null && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val b = Rect()
                n.getBoundsInScreen(b)
                tap(b.exactCenterX(), b.exactCenterY())
                return true
            }
            if (ocrTapElement(Regex.escape(fileName))) return true
            Thread.sleep(1500)
        }
        return false
    }

    private fun fail(msg: String): Nothing {
        throw RuntimeException(msg)
    }

    private fun findEditable(): AccessibilityNodeInfo? {
        val list = findNodes { it.isEditable }
        return list.firstOrNull { it.className?.toString() == "android.widget.EditText" } ?: list.firstOrNull()
    }

    private fun findPromptField(): AccessibilityNodeInfo? {
        val edits = findNodes { it.isEditable }
        return edits.firstOrNull {
            val t = (it.text ?: "").toString() + (it.hintText ?: "")
            t.contains("Опишите", true) || t.contains("видео, которое", true)
        } ?: edits.lastOrNull()
    }

    private fun typePrompt(prompt: String): Boolean {
        var n = findPromptField() ?: findEditable()
        if (n == null && ocrTapElement("Опишите видео|Опишите|Сообщение|Describe")) {
            Thread.sleep(900)
            n = findPromptField() ?: findEditable()
        }
        if (n == null) return false
        val b = Rect()
        n.getBoundsInScreen(b)
        BotLog.add("Поле найдено — вставляю промпт")
        tap(b.exactCenterX(), b.exactCenterY())
        Thread.sleep(1200)
        val clipHint = prompt.trim().take(12)
        if (clipHint.length >= 4) {
            val chips = findNodes {
                val t = (it.text ?: "").toString()
                t.contains(clipHint.take(8), true) || t.startsWith(clipHint.take(6), true)
            }
            if (chips.isNotEmpty() && clickNode(smallestNode(chips))) {
                BotLog.add("Промпт вставлен (чип буфера)")
                return true
            }
            if (ocrTapElement(Regex.escape(clipHint.take(8)))) {
                BotLog.add("Промпт вставлен (OCR чип буфера)")
                return true
            }
        }
        val scr = Rect(); rootInActiveWindow?.getBoundsInScreen(scr)
        if (scr.height() > 0) {
            tap(scr.left + scr.width() * 0.48f, scr.top + scr.height() * 0.57f)
            Thread.sleep(600)
            if (n.text?.toString()?.isNotBlank() == true && n.text.toString() != "Сообщение") {
                BotLog.add("Промпт вставлен (чип по координатам)")
                return true
            }
        }
        longPress(b.exactCenterX(), b.exactCenterY())
        Thread.sleep(900)
        if (tapPaste() || ocrTapElement("Вставить|Paste")) {
            BotLog.add("Промпт вставлен (долгий тап → Вставить)")
            return true
        }
        if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE, Bundle())) {
            BotLog.add("Промпт вставлен (буфер)")
            return true
        }
        val args = Bundle()
        args.putCharSequence("ACTION_ARGUMENT_SET_TEXT_CHAR_SEQUENCE", prompt)
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) BotLog.add("Промпт вставлен (SET_TEXT)")
        return ok
    }

    private fun tapPaste(): Boolean {
        val keys = listOf("Вставить", "Paste")
        val nodes = findNodes { matches(it, keys, false) }
        if (nodes.isNotEmpty()) return clickNode(smallestNode(nodes))
        return false
    }

    private fun sendPrompt(): Boolean {
        val keys = listOf("Отправить", "Send", "Отправить сообщение", "Send message", "Отправка", "Submit")
        val skip = listOf("микрофон", "голос", "voice", "mic", "dictation", "камера", "camera")
        val nodes = findNodes { n ->
            val d = (n.contentDescription ?: "").toString()
            val t = (n.text ?: "").toString()
            if (skip.any { d.contains(it, true) || t.contains(it, true) }) false
            else keys.any { d.equals(it, true) || t.equals(it, true) }
        }
        if (nodes.isNotEmpty() && clickNode(smallestNode(nodes))) {
            BotLog.add("Отправлено")
            return true
        }
        val field = findPromptField() ?: findEditable()
        if (field != null) {
            try {
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Exception) {}
            Thread.sleep(500)
        }
        val scr = Rect()
        rootInActiveWindow?.getBoundsInScreen(scr)
        if (scr.width() > 0) {
            BotLog.add("Жму кнопку отправки на клавиатуре")
            tap(scr.left + scr.width() * 0.92f, scr.top + scr.height() * 0.94f)
            return true
        }
        return false
    }

    private fun findNodes(root: AccessibilityNodeInfo? = null, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        if (root != null) {
            searchNodes(root, pred, out)
            return out
        }
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == packageName) continue
                searchNodes(r, pred, out)
            }
        } catch (e: Exception) {}
        if (out.isEmpty()) {
            val r = rootInActiveWindow ?: return out
            searchNodes(r, pred, out)
        }
        return out
    }

    private fun searchNodes(r: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean, out: ArrayList<AccessibilityNodeInfo>) {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(r)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            try {
                if (pred(n)) out.add(n)
            } catch (_: Exception) {}
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                q.add(c)
            }
        }
    }

    private fun matches(n: AccessibilityNodeInfo, strs: List<String>, exact: Boolean): Boolean {
        val t = (n.text ?: "").toString().trim()
        val d = (n.contentDescription ?: "").toString().trim()
        for (s in strs) {
            val hit = if (exact) t.equals(s, true) else t.contains(s, true)
            if (hit || d.equals(s, true)) return true
        }
        return false
    }

    private fun clickableParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p: AccessibilityNodeInfo? = n
        for (i in 0 until 6) {
            p = p?.parent ?: break
            if (p.isClickable) return p
        }
        return null
    }

    private fun smallestNode(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo {
        return nodes.minByOrNull { n ->
            val b = Rect()
            n.getBoundsInScreen(b)
            val a = b.width() * b.height()
            if (a <= 0) Int.MAX_VALUE else a
        } ?: nodes[0]
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        val b = Rect()
        n.getBoundsInScreen(b)
        if (b.width() < 8 || b.height() < 8) {
            val p = clickableParent(n)
            if (p != null) p.getBoundsInScreen(b)
        }
        if (b.width() < 8 || b.height() < 8) return false
        try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) {}
        try { clickableParent(n)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) {}
        return tap(b.exactCenterX(), b.exactCenterY())
    }

    private fun tapByText(strs: List<String>, timeout: Long, optional: Boolean = false): Boolean {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            checkStop()
            val nodes = findNodes { matches(it, strs, false) }
            if (nodes.isNotEmpty() && clickNode(smallestNode(nodes))) return true
            if (ocrTapElement(strs.joinToString("|"))) return true
            Thread.sleep(1200)
        }
        if (!optional) BotLog.add("Не нашёл на экране: ${strs.joinToString("/")}")
        return false
    }

    private fun tapExact(strs: List<String>, timeout: Long, optional: Boolean = false): Boolean {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            checkStop()
            val nodes = findNodes { n ->
                val t = (n.text ?: "").toString().trim()
                strs.any { t.equals(it, true) }
            }
            if (nodes.isNotEmpty() && clickNode(smallestNode(nodes))) return true
            Thread.sleep(1200)
        }
        if (!optional) BotLog.add("Не нашёл точно: ${strs.joinToString("/")}")
        return false
    }

    private fun tapDescOrText(keys: List<String>, timeout: Long = 4_000): Boolean {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            checkStop()
            val nodes = findNodes { n ->
                val d = (n.contentDescription ?: "").toString()
                val t = (n.text ?: "").toString()
                keys.any { d.contains(it, true) || t.contains(it, true) }
            }
            if (nodes.isNotEmpty() && clickNode(smallestNode(nodes))) return true
            Thread.sleep(1000)
        }
        return false
    }

    private fun screenHasText(strs: List<String>): Boolean {
        if (findNodes { matches(it, strs, false) }.isNotEmpty()) return true
        val ocr = ocrText() ?: return false
        return strs.any { ocr.contains(it, true) }
    }

    private fun tap(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 120))
            .build()
        return dispatchAndWait(g, 1500)
    }

    private fun longPress(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 1100))
            .build()
        return dispatchAndWait(g, 2500)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long): Boolean {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur))
            .build()
        return dispatchAndWait(g, dur + 1500)
    }

    private fun dispatchAndWait(g: GestureDescription, waitMs: Long): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val run = Runnable {
            val posted = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok.set(true)
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (!posted) latch.countDown()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
        latch.await(waitMs, TimeUnit.MILLISECONDS)
        Thread.sleep(120)
        return ok.get()
    }

    private fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val ref = AtomicReference<Bitmap?>()
        val latch = CountDownLatch(1)
        takeScreenshot(Display.DEFAULT_DISPLAY, ocrExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    ref.set(hw?.copy(Bitmap.Config.ARGB_8888, false))
                } catch (_: Throwable) {
                } finally {
                    result.hardwareBuffer.close()
                    latch.countDown()
                }
            }
            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        })
        latch.await(5, TimeUnit.SECONDS)
        return ref.get()
    }

    private fun ocrText(): String? {
        val bmp = screenshot() ?: return null
        return try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 15, TimeUnit.SECONDS).text
        } catch (e: Exception) {
            null
        }
    }

    private fun ocrTapElement(pattern: String): Boolean {
        val bmp = screenshot() ?: return false
        val res = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return false
        }
        val re = Regex(pattern, RegexOption.IGNORE_CASE)
        for (bl in res.textBlocks) for (l in bl.lines) {
            if (re.containsMatchIn(l.text)) {
                val bb = l.boundingBox ?: continue
                tap(bb.exactCenterX(), bb.exactCenterY())
                return true
            }
        }
        for (bl in res.textBlocks) for (l in bl.lines) for (el in l.elements) {
            if (re.containsMatchIn(el.text)) {
                val bb = el.boundingBox ?: continue
                tap(bb.exactCenterX(), bb.exactCenterY())
                return true
            }
        }
        return false
    }

    private fun waitVideo(sinceSec: Long, timeoutMs: Long, dst: File): File? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            checkStop()
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED)
                    contentResolver.query(uri, proj, "${MediaStore.MediaColumns.DATE_ADDED} >= ?", arrayOf("$sinceSec"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cur ->
                        while (cur.moveToNext()) {
                            val name = cur.getString(1) ?: ""
                            val mime = cur.getString(2) ?: ""
                            if (mime.startsWith("video") || name.endsWith(".mp4") || name.endsWith(".webm")) {
                                val src = ContentUris.withAppendedId(uri, cur.getLong(0))
                                contentResolver.openInputStream(src)?.use { input ->
                                    dst.outputStream().use { input.copyTo(it, 65536) }
                                }
                                if (dst.length() > 0) return dst
                            }
                        }
                    }
                } catch (e: Exception) {
                    BotLog.add("waitVideo: ${e.message}")
                }
            } else {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val f = dl.listFiles()?.filter {
                    it.isFile && (it.name.endsWith(".mp4") || it.name.endsWith(".webm")) && it.lastModified() >= sinceSec * 1000
                }?.maxByOrNull { it.lastModified() }
                if (f != null) {
                    f.copyTo(dst, overwrite = true)
                    return dst
                }
            }
            Thread.sleep(3000)
        }
        return null
    }
}
