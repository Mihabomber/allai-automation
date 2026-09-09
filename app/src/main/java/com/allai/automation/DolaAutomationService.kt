package com.allai.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
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
        private const val LOGIN_BTNS = "Sign In\u0000Sign in\u0000Войти\u0000Log in"
        private const val GOOGLE_BTNS = "Sign in with Google\u0000Continue with Google\u0000Войти через Google"
        private const val GENERATE_BTNS = "Generate Video\u0000Generate\u0000Создать видео"
        private const val DOWNLOAD_BTNS = "Download\u0000Скачать\u0000Save\u0000Сохранить"
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val ocrExecutor = Executors.newSingleThreadExecutor()

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
        BotLog.add("Открываю Dola: ${Config.dolaUrl}")
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.dolaUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(6000)
        checkStop()

        waitForPromptField(30_000)
        if (!promptVisible() && tapByText(LOGIN_BTNS.split("\u0000"), 8_000)) {
            BotLog.add("Вход: жми кнопку Google")
            tapByText(GOOGLE_BTNS.split("\u0000"), 10_000)
            BotLog.add("Выбери аккаунт Google вручную, дальше сам")
            waitForPromptField(180_000)
        }
        checkStop()
        tapByText(listOf("Pro"), 4_000, optional = true)
        tapByText(listOf("720p"), 3_000, optional = true)
        tapByText(listOf("15s"), 3_000, optional = true)

        BotLog.add("Генерирую PART1")
        val f1 = generate(job.part1, job.id, "part1")
        val out = mutableListOf(f1)

        if (job.part2.isNotEmpty()) {
            checkStop()
            BotLog.add("Генерирую PART2")
            val f2 = generate(job.part2, job.id, "part2")
            out.add(f2)
        }
        BotLog.add("Задача выполнена: ${out.size} видео")
        return out
    }

    private fun generate(prompt: String, jobId: String, tag: String): File {
        if (!typePrompt(prompt)) fail("Не нашёл поле Prompt")
        if (!tapByText(GENERATE_BTNS.split("\u0000"), 12_000)) fail("Не нашёл кнопку Generate")
        checkStop()
        BotLog.add("Жду генерацию ($tag)...")
        waitForText(listOf("Download", "Скачать"), 25 * 60_000)
        checkStop()
        val before = System.currentTimeMillis() / 1000 - 5
        if (!tapByText(DOWNLOAD_BTNS.split("\u0000"), 10_000)) fail("Не нашёл кнопку Download")
        val dst = File(getExternalFilesDir("jobs"), "${jobId}_$tag.mp4")
        val f = waitVideo(before, 8 * 60_000, dst) ?: fail("Видео не скачалось")
        BotLog.add("$tag готов: ${f.length() / 1024} KB")
        return f
    }

    private fun checkStop() {
        if (stopRequested) throw RuntimeException("Остановлено пользователем")
    }

    private fun fail(msg: String): Nothing {
        throw RuntimeException(msg)
    }

    private fun promptVisible(): Boolean = findEditable() != null || screenHasText(listOf("Prompt", "Промпт"))

    private fun waitForPromptField(timeout: Long) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            checkStop()
            if (promptVisible()) return
            Thread.sleep(1500)
        }
        BotLog.add("Поле Prompt не найдено, продолжаю вслепую")
    }

    private fun findEditable(): AccessibilityNodeInfo? {
        val list = findNodes { it.isEditable }
        return list.firstOrNull { it.className?.toString() == "android.widget.EditText" } ?: list.firstOrNull()
    }

    private fun typePrompt(prompt: String): Boolean {
        var n = findEditable()
        if (n == null) {
            if (ocrTapElement("Prompt|Промпт")) {
                Thread.sleep(800)
                n = findNodes { it.isEditable && it.isFocused }.firstOrNull() ?: findEditable()
            }
        }
        if (n == null) return false
        val args = Bundle()
        args.putCharSequence("ACTION_ARGUMENT_SET_TEXT_CHAR_SEQUENCE", prompt)
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("allai", prompt))
        return n.performAction(AccessibilityNodeInfo.ACTION_PASTE, Bundle())
    }

    private fun findNodes(root: AccessibilityNodeInfo? = rootInActiveWindow, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val r = root ?: return out
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
        return out
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

    private fun screenHasText(strs: List<String>): Boolean {
        if (findNodes { matches(it, strs, false) }.isNotEmpty()) return true
        val ocr = ocrText() ?: return false
        return strs.any { ocr.contains(it, true) }
    }

    private fun waitForText(strs: List<String>, timeout: Long) {
        val end = System.currentTimeMillis() + timeout
        var lastLog = 0L
        while (System.currentTimeMillis() < end) {
            checkStop()
            if (screenHasText(strs)) return
            if (System.currentTimeMillis() - lastLog > 60_000) {
                BotLog.add("Всё ещё генерируется...")
                lastLog = System.currentTimeMillis()
            }
            Thread.sleep(4000)
        }
        fail("Таймаут ожидания: ${strs.joinToString("/")}")
    }

    private fun tap(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val ref = AtomicReference<Bitmap?>()
        val latch = CountDownLatch(1)
        takeScreenshot(Display.DEFAULT_DISPLAY, ocrExecutor, object : TakeScreenshotCallback {
            override fun onScreenshotSuccess(result: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    ref.set(hw?.copy(Bitmap.Config.ARGB_8888, false))
                } catch (_: Throwable) {
                } finally {
                    result.hardwareBuffer.close()
                    latch.countDown()
                }
            }
            override fun onScreenshotFailed(errorCode: Int) {
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
        for (b in res.textBlocks) for (l in b.lines) for (el in l.elements) {
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
