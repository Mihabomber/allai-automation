package com.allai.automation

import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object DiscordPoller {
    @Volatile var stopRequested = false
    private const val API = "https://discord.com/api/v10"
    private val UA = "Mozilla/5.0 (Linux; Android 14) allai/1.3"
    @Volatile private var lastEmptyLog = 0L

    private class HttpResult(val code: Int, val body: String)

    private fun conn(path: String): HttpURLConnection {
        val c = URL(API + path).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", Config.token)
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 10000
        c.readTimeout = 15000
        return c
    }

    private fun get(path: String): HttpResult = try {
        val c = conn(path)
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        HttpResult(code, body)
    } catch (e: Exception) {
        HttpResult(-1, e.message ?: "network")
    }

    private fun post(path: String, json: String): HttpResult = try {
        val c = conn(path)
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.outputStream.write(json.toByteArray())
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        HttpResult(code, body)
    } catch (e: Exception) {
        HttpResult(-1, e.message ?: "network")
    }

    fun test() {
        thread(name = "allai-test") {
            if (Config.token.isBlank()) { BotLog.add("ТЕСТ: токен пуст"); return@thread }
            val me = get("/users/@me")
            if (me.code != 200) { BotLog.add("ТЕСТ: токен невалиден (HTTP ${me.code}) — скопируй заново без кавычек"); return@thread }
            BotLog.add("ТЕСТ: токен ок — аккаунт @${JSONObject(me.body).optString("username")}")
            val cid = Config.channel
            if (cid.isBlank()) { BotLog.add("ТЕСТ: поле ID пустое — вставь ID пользователя (друга) или канала"); return@thread }
            val ch = get("/channels/$cid/messages?limit=3")
            when {
                ch.code == 200 -> {
                    val arr = JSONArray(ch.body)
                    if (arr.length() == 0) BotLog.add("ТЕСТ: канал ок, но сообщений нет")
                    else for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        val t = m.optString("content")
                        val short = if (t.length > 60) t.substring(0, 60) + "…" else t
                        BotLog.add("ТЕСТ чат: ${m.optJSONObject("author")?.optString("username")}: $short")
                    }
                }
                ch.code == 404 -> resolveDm(cid)
                ch.code == 403 -> BotLog.add("ТЕСТ: нет доступа к каналу (403)")
                else -> BotLog.add("ТЕСТ: HTTP ${ch.code}")
            }
        }
    }

    private fun resolveDm(id: String) {
        BotLog.add("Discord: канал не найден (404) — пробую открыть ЛС с $id…")
        val r = post("/users/@me/channels", JSONObject().put("recipient_id", id).toString())
        if (r.code in 200..299) {
            val chId = JSONObject(r.body).optString("id")
            if (chId.isNotEmpty()) {
                Config.channel = chId
                Config.lastMsgId = "0"
                Config.processed = ""
                BotLog.add("Discord: ЛС найден (ID $chId) — сохранено, пользуйся")
                return
            }
        }
        BotLog.add("Discord: $id — не пользователь и не канал (HTTP ${r.code}). Режим разработчика → ПКМ по диалогу ЛС → Копировать ID")
    }

    fun nextJob(): Job? {
        if (!Config.ready()) return null
        val r = get("/channels/${Config.channel}/messages?limit=10")
        when {
            r.code == -1 -> { BotLog.add("Discord: сеть — ${r.body}"); return null }
            r.code == 401 -> { BotLog.add("Discord: токен невалиден (401)"); return null }
            r.code == 403 -> { BotLog.add("Discord: нет доступа (403)"); return null }
            r.code == 404 -> { resolveDm(Config.channel); return null }
            r.code !in 200..299 -> { BotLog.add("Discord: HTTP ${r.code}"); return null }
        }
        val arr = JSONArray(r.body)
        val done = Config.processed.split(",").filter { it.isNotBlank() }.toCollection(LinkedHashSet())
        var job: Job? = null
        var jobId = ""
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val id = m.getString("id")
            if (id in done) continue
            val j = parse(m)
            if (j != null) { job = j; jobId = id; break }
        }
        if (job != null) {
            for (i in 0 until arr.length()) {
                val id = arr.getJSONObject(i).getString("id")
                if (id.toLong() <= jobId.toLong()) done.add(id)
            }
            Config.processed = done.toList().takeLast(60).joinToString(",")
        }
        if (job == null && arr.isNotEmpty()) {
            val now = System.currentTimeMillis()
            if (now - lastEmptyLog > 60_000) {
                val sb = StringBuilder("Опрос: ${arr.length()} сообщ. — задач нет:")
                for (i in 0 until minOf(3, arr.length())) {
                    val m = arr.getJSONObject(i)
                    val t = m.optString("content")
                    val s = if (t.isBlank()) "(пусто/вложения)" else if (t.length > 30) t.take(30) + "…" else t
                    sb.append(" | ${m.optJSONObject("author")?.optString("username")}: $s")
                }
                BotLog.add(sb.toString())
                lastEmptyLog = now
            }
        }
        return job
    }

    private fun parse(m: JSONObject): Job? {
        var text = m.optString("content")
        val em = m.optJSONArray("embeds")
        if (em != null) {
            for (i in 0 until em.length()) {
                val e = em.getJSONObject(i)
                text += "\n" + e.optString("title") + "\n" + e.optString("description")
            }
        }
        val up = text.uppercase()
        val i1 = up.indexOf("[PART1]")
        if (i1 < 0) return null
        val i2 = up.indexOf("[PART2]", i1)
        val i3 = if (i2 >= 0) up.indexOf("[PART3]", i2) else -1
        val p1 = text.substring(i1 + 7, if (i2 >= 0) i2 else text.length).trim()
        val p2 = if (i2 >= 0) text.substring(i2 + 7, if (i3 >= 0) i3 else text.length).trim() else ""
        if (p1.isEmpty()) return null
        val images = ArrayList<String>(2)
        var audio: String? = null
        val att = m.optJSONArray("attachments")
        if (att != null) {
            for (i in 0 until att.length()) {
                val a = att.getJSONObject(i)
                val ct = a.optString("content_type")
                val fn = a.optString("filename")
                val url = a.optString("url")
                if (ct.startsWith("image/") && images.size < 2) images.add(url)
                else if (ct.startsWith("audio/") && audio == null) audio = url
                else if (ct.isEmpty()) {
                    when {
                        fn.endsWith(".png") || fn.endsWith(".jpg") || fn.endsWith(".jpeg") || fn.endsWith(".webp") ->
                            if (images.size < 2) images.add(url)
                        fn.endsWith(".mp3") || fn.endsWith(".wav") || fn.endsWith(".m4a") || fn.endsWith(".ogg") ||
                            fn.endsWith(".flac") || fn.endsWith(".aac") || fn.endsWith(".opus") ->
                            if (audio == null) audio = url
                    }
                }
            }
        }
        BotLog.add("Новая задача ${m.getString("id")} (${m.optJSONObject("author")?.optString("username") ?: "?"}): фото ${images.size}, аудио ${if (audio != null) "да" else "нет"}")
        return Job(m.getString("id"), m.optString("channel_id", Config.channel), p1, p2, images, audio)
    }

    fun send(channel: String, text: String): Boolean {
        return try {
            val r = post("/channels/$channel/messages", JSONObject().put("content", text).toString())
            if (r.code !in 200..299) BotLog.add("Discord send: HTTP ${r.code}")
            r.code in 200..299
        } catch (e: Exception) {
            BotLog.add("Discord send: ${e.message}")
            false
        }
    }

    fun sendVideo(channel: String, text: String, file: File): Boolean {
        return try {
            val boundary = "----allai${System.currentTimeMillis()}"
            val c = conn("/channels/$channel/messages")
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.doOutput = true
            val out = DataOutputStream(c.outputStream)
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n${JSONObject().put("content", text)}\r\n")
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"${file.name}\"\r\nContent-Type: video/mp4\r\n\r\n")
            file.inputStream().use { it.copyTo(out, 65536) }
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush()
            val code = c.responseCode
            if (code !in 200..299) BotLog.add("Загрузка видео: HTTP $code (лимит Discord?)")
            c.disconnect()
            code in 200..299
        } catch (e: Exception) {
            BotLog.add("Загрузка видео: ${e.message}")
            false
        }
    }

    fun download(url: String, dst: File): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 15000
        c.readTimeout = 30000
        dst.outputStream().use { o -> c.inputStream.use { it.copyTo(o, 65536) } }
        c.disconnect()
        dst.length() > 0
    } catch (e: Exception) {
        BotLog.add("Скачивание файла: ${e.message}")
        false
    }

    data class Job(val id: String, val channel: String, val part1: String, val part2: String, val images: List<String>, val audio: String?)
}

private fun JSONArray.isNotEmpty() = length() > 0
