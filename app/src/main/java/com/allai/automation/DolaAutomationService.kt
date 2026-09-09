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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DolaAutomationService : AccessibilityService() {

    companion object {
        @Volatile var instance: DolaAutomationService? = null
        @Volatile var stopRequested = false
        private const val LOGIN_BTNS = "Войти\u0000Войдите\u0000Вход\u0000Sign In\u0000Sign in\u0000Log in"
        private const val GOOGLE_BTNS = "Продолжить с Google\u0000Продолжить через Google\u0000Войти через Google\u0000Sign in with Google\u0000Continue with Google\u0000Google"
        private const val DOWNLOAD_BTNS = "Скачать\u0000Download\u0000Save\u0000Сохранить"
        private const val READY = 0
        private const val LIMIT = 1
        private const val TIMEOUT = 2
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val dolaPkgGuesses = listOf("com.dola.ai", "ai.dola.app", "com.dola.android", "com.dolai.app", "app.dola")
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
        waitChatReady(90_000)
        handleLogin()
        handleBirthday()
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
                    val f = watchAndDownload(tag) ?: fail("Видео не скачалось")
                    BotLog.add("$tag готов: ${f.length() / 1024} KB")
                    return f
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
            val hasField = findEditable() != null || screenHasText(listOf("Сообщение", "Опишите", "Создано ИИ"))
            if (hasField && !loginVisible()) return
            if (retryLogin && loginVisible()) handleLogin()
            Thread.sleep(1500)
        }
        BotLog.add("Окно Dola не подтвердилось, продолжаю вслепую")
    }

    private fun handleLogin() {
        if (!loginVisible()) return
        BotLog.add("Вижу экран входа — жму «Продолжить с Google»")
        if (tapByText(GOOGLE_BTNS.split("\u0000"), 15_000, optional = true)) {
            BotLog.add("Нажал «Продолжить с Google»")
        } else if (ocrTapElement("Google")) {
            BotLog.add("Нажал «Продолжить с Google» (OCR)")
        } else {
            val scr = Rect()
            rootInActiveWindow?.getBoundsInScreen(scr)
            if (scr.height() > 0) {
                BotLog.add("Жму кнопку Google по координатам")
                tap(scr.left + scr.width() * 0.5f, scr.top + scr.height() * 0.545f)
            }
        }
        Thread.sleep(2000)
        pickGoogleAccount(90_000)
        tapConsent(30_000)
        handleBirthday()
        waitChatReady(180_000, retryLogin = false)
        if (loginVisible()) {
            fail("Вход в Dola не удался — сделай вручную: «Продолжить с Google» → выбери аккаунт, потом снова запусти бота")
        }
        BotLog.add("Вход завершён")
    }

    private fun birthdayVisible(): Boolean {
        val keys = listOf("дата рождения", "Дата рождения", "date of birth", "Date of Birth", "Год", "год")
        if (findNodes { matches(it, keys, false) }.isNotEmpty()) return true
        return screenHasText(listOf("дата рождения", "Дата рождения", "date of birth"))
    }

    private fun handleBirthday() {
        val deadline = System.currentTimeMillis() + 25_000
        var seen = false
        while (System.currentTimeMillis() < deadline) {
            checkStop()
            if (birthdayVisible()) {
                seen = true
                break
            }
            Thread.sleep(1500)
        }
        if (!seen) return
        BotLog.add("Экран даты рождения — прокручиваю год к 2000")
        if (!scrollForText("2000", 30)) {
            BotLog.add("Не нашёл 2000 — выбери год сам, я жду 10с")
            var t = 0
            while (t < 10) {
                checkStop()
                Thread.sleep(1000)
                t++
                if (findNodes { (it.text ?: "").toString().trim() == "2000" }.isNotEmpty()) t = 10
            }
        }
        Thread.sleep(1200)
        tapByText(listOf("Далее", "Готово", "ОК", "OK", "Подтвердить", "Продолжить", "Сохранить", "Done", "Next"), 12_000, optional = true)
        Thread.sleep(2000)
        BotLog.add("Дату рождения прошёл")
    }

    private fun scrollForText(pattern: String, maxSwipes: Int): Boolean {
        val re = Regex(pattern)
        val scr = Rect()
        for (dir in listOf(1, 0)) {
            for (i in 0 until maxSwipes) {
                checkStop()
                val hit = findNodes { re.matches((it.text ?: "").toString().trim()) }
                if (hit.isNotEmpty()) {
                    val n = hit[0]
                    if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    val p = clickableParent(n)
                    if (p != null && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    val b = Rect()
                    n.getBoundsInScreen(b)
                    tap(b.exactCenterX(), b.exactCenterY())
                    return true
                }
                if (ocrTapElement("\\b" + pattern + "\\b")) return true
                rootInActiveWindow?.getBoundsInScreen(scr)
                if (scr.height() == 0) continue
                val cx = scr.exactCenterX()
                val yTop = scr.bottom - scr.height() * 0.30f
                val yBottom = scr.bottom - scr.height() * 0.60f
                if (dir == 1) swipe(cx, yTop, cx, yBottom, 350) else swipe(cx, yBottom, cx, yTop, 350)
                Thread.sleep(1200)
            }
        }
        return false
    }

    private fun loginVisible(): Boolean {
        val keys = listOf("Войти", "Войдите", "Вход", "Sign in", "Sign In", "Log in", "Log In", "Продолжить с Google", "Продолжить на телефоне", "Продолжить с Facebook", "Вход через Google")
        if (findNodes { matches(it, keys, false) }.isNotEmpty()) return true
        return screenHasText(listOf("Войдите", "Войти", "Sign in", "Log in", "Продолжить с Google"))
    }

    private fun tapConsent(timeout: Long) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            checkStop()
            if (tapByText(listOf("Продолжить", "I agree", "Согласен", "Соглашаюсь", "Далее"), 2_000, optional = true)) {
                Thread.sleep(2500)
            } else {
                return
            }
        }
    }

    private fun pickGoogleAccount(timeout: Long) {
        BotLog.add("Жду выбор аккаунта Google…")
        val end = System.currentTimeMillis() + timeout
        var hinted = false
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
                val ok = (top.isClickable && top.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ||
                    clickableParent(top)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (!ok) {
                    val b = Rect()
                    top.getBoundsInScreen(b)
                    tap(b.exactCenterX(), b.exactCenterY())
                }
                BotLog.add("Выбран верхний аккаунт Google")
                Thread.sleep(4000)
                return
            }
            if (ocrTapElement("@gmail")) {
                BotLog.add("Выбран аккаунт Google (OCR)")
                Thread.sleep(4000)
                return
            }
            if (!hinted && System.currentTimeMillis() > end - timeout + 20_000) {
                hinted = true
                BotLog.add("Не вижу список аккаунтов — выбери аккаунт сам, я жду")
            }
            Thread.sleep(1500)
        }
        BotLog.add("Выбор аккаунта не подтверждён, продолжаю")
    }

    private fun switchToPro() {
        if (proChipOn()) return
        if (!tapByText(listOf("Fast", "Быстрый"), 5_000, optional = true)) return
        Thread.sleep(1500)
        if (!tapByText(listOf("Продвинутая модель", "Pro"), 6_000, optional = true)) {
            BotLog.add("Пункт Pro не найден — работаю с текущим режимом")
        }
        Thread.sleep(2000)
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
        tapByText(listOf("Создание контента"), 6_000, optional = true)
        Thread.sleep(1500)
        tapExact(listOf("Видео"), 4_000, optional = true)
        Thread.sleep(1200)
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
        val before = System.currentTimeMillis() / 1000 - 5
        if (!tapByText(watchWords, 12_000, optional = true)) {
            BotLog.add("Нет «Смотреть видео» — пробую скачать из открытого окна")
        }
        Thread.sleep(5000)
        checkStop()
        val end = System.currentTimeMillis() + 6 * 60_000
        while (System.currentTimeMillis() < end) {
            checkStop()
            tryDownloadInBrowser()
            val dst = File(getExternalFilesDir("jobs"), "allai_$tag.mp4")
            val f = waitVideo(before, 20_000, dst)
            if (f != null) {
                backToDola()
                return f
            }
            Thread.sleep(2000)
        }
        return null
    }

    private fun tryDownloadInBrowser() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        val web = findNodes { it.className?.toString().equals("android.webkit.WebView", true) }.firstOrNull()
        val isBrowser = pkg.contains("chrome", true) || pkg.contains("browser", true) || web != null
        if (!isBrowser) return
        if (tapByText(DOWNLOAD_BTNS.split("\u0000"), 1_500, optional = true)) {
            Thread.sleep(2500)
            return
        }
        val b = Rect()
        if (web != null) web.getBoundsInScreen(b) else {
            root.getBoundsInScreen(b)
            b.top += (b.height() * 0.10).toInt()
        }
        tap(b.exactCenterX(), b.exactCenterY())
        Thread.sleep(1000)
        tap(b.left + b.width() * 0.90f, b.bottom - b.height() * 0.11f)
        Thread.sleep(1500)
        if (tapByText(DOWNLOAD_BTNS.split("\u0000"), 5_000, optional = true)) Thread.sleep(2500)
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
        if (!tapDescOrText(listOf("Меню", "Menu", "Открыть меню", "navigation", "drawer"))) {
            val scr = Rect()
            rootInActiveWindow?.getBoundsInScreen(scr)
            tap(scr.left + scr.width() * 0.065f, scr.top + scr.height() * 0.072f)
        }
        Thread.sleep(1800)
        if (!tapDescOrText(listOf("Настройки", "Settings", "gear"))) {
            val scr2 = Rect()
            rootInActiveWindow?.getBoundsInScreen(scr2)
            tap(scr2.left + scr2.width() * 0.77f, scr2.top + scr2.height() * 0.765f)
        }
        Thread.sleep(1800)
        if (!tapByText(listOf("Аккаунт Dola"), 8_000)) fail("Не нашёл «Аккаунт Dola»")
        if (!tapByText(listOf("Удалить учетную запись"), 8_000)) fail("Не нашёл «Удалить учетную запись»")
        Thread.sleep(1200)
        if (!tapExact(listOf("Удалить"), 8_000)) fail("Не подтвердил удаление")
        Thread.sleep(1500)
        if (!tapByText(listOf("Удалить сейчас"), 10_000, optional = true) && !ocrTapElement("Удалить сейчас")) {
            fail("Не нашёл «Удалить сейчас»")
        }
        BotLog.add("Аккаунт удалён — жду пересоздания")
        Thread.sleep(6000)
        tapByText(listOf("Продолжить", "Continue", "Начать", "Get started"), 5_000, optional = true)
        handleLogin()
        waitChatReady(120_000)
        handleLogin()
        handleBirthday()
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
        tap(b.exactCenterX(), b.exactCenterY())
        Thread.sleep(800)
        repeat(3) {
            longPress(b.exactCenterX(), b.exactCenterY())
            Thread.sleep(1200)
            if (tapPaste()) {
                BotLog.add("Промпт вставлен (долгий тап → Вставить)")
                return true
            }
        }
        if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE, Bundle())) {
            BotLog.add("Промпт вставлен (буфер)")
            return true
        }
        val args = Bundle()
        args.putCharSequence("ACTION_ARGUMENT_SET_TEXT_CHAR_SEQUENCE", prompt)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun tapPaste(): Boolean {
        val keys = listOf("Вставить", "Paste")
        val nodes = findNodes { matches(it, keys, false) }
        for (nd in nodes) {
            if (nd.isClickable && nd.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val p = clickableParent(nd)
            if (p != null && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        if (nodes.isNotEmpty()) {
            val b = Rect()
            nodes[0].getBoundsInScreen(b)
            tap(b.exactCenterX(), b.exactCenterY())
            return true
        }
        return ocrTapElement("Вставить|Paste")
    }

    private fun sendPrompt(): Boolean {
        val keys = listOf("Отправить", "Send", "Отправить сообщение", "Send message", "Отправка", "Submit")
        val nodes = findNodes { n ->
            val d = (n.contentDescription ?: "").toString()
            val t = (n.text ?: "").toString()
            keys.any { d.equals(it, true) || t.equals(it, true) }
        }
        for (n in nodes) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                BotLog.add("Отправлено")
                return true
            }
            val p = clickableParent(n)
            if (p != null && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                BotLog.add("Отправлено")
                return true
            }
        }
        val field = findPromptField() ?: findEditable()
        if (field != null) {
            val b = Rect()
            field.getBoundsInScreen(b)
            val scr = Rect()
            rootInActiveWindow?.getBoundsInScreen(scr)
            if (scr.width() > 0) {
                val x = b.right + b.height() * 0.9f
                val cx = if (x < scr.right - 30f) x else scr.right - 40f
                tap(cx, b.exactCenterY())
                BotLog.add("Отправлено (координаты)")
                return true
            }
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

    private fun tapByText(strs: List<String>, timeout: Long, optional: Boolean = false): Boolean {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            checkStop()
            val nodes = findNodes { matches(it, strs, false) }
            for (n in nodes) {
                if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val p = clickableParent(n)
                if (p != null && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            if (nodes.isNotEmpty()) {
                val b = Rect(); nodes[0].getBoundsInScreen(b)
                tap(b.exactCenterX(), b.exactCenterY())
                return true
            }
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
            for (n in nodes) {
                if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val p = clickableParent(n)
                if (p != null && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            if (nodes.isNotEmpty()) {
                val b = Rect(); nodes[0].getBoundsInScreen(b)
                tap(b.exactCenterX(), b.exactCenterY())
                return true
            }
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
            for (n in nodes) {
                if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val p = clickableParent(n)
                if (p != null && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            if (nodes.isNotEmpty()) {
                val b = Rect(); nodes[0].getBoundsInScreen(b)
                tap(b.exactCenterX(), b.exactCenterY())
                return true
            }
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
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun longPress(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 900))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long): Boolean {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur))
            .build()
        return dispatchGesture(g, null, null)
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
