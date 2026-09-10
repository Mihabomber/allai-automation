package com.allai.automation

import kotlin.concurrent.thread
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DiscordPoller {
    @Volatile var stopRequested = false
    private const val API = "https://discord.com/api/v10"
    private val UA = "Mozilla/5.0 (Linux; Android 14) allai/1.8"
    @Volatile private var lastEmptyLog = 0L

    private class HttpResult(val code: Int, val body: String)

    private val rawClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(DohDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    object DohDns : Dns {
        private val cache = ConcurrentHashMap<String, List<InetAddress>>()
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                val r = Dns.SYSTEM.lookup(hostname)
                cache[hostname] = r
                return r
            } catch (e: UnknownHostException) {
                cache[hostname]?.let { return it }
            }
            val ip = dohResolve(hostname)
            if (ip != null) {
                val ia = InetAddress.getByAddress(hostname, InetAddress.getByName(ip).address)
                val list = listOf(ia)
                cache[hostname] = list
                BotLog.add("DoH: системный DNS не работает — $hostname → $ip (запасной)")
                return list
            }
            throw UnknownHostException(hostname)
        }

        private fun dohResolve(host: String): String? {
            for (base in listOf("https://1.1.1.1/dns-query?name=", "https://8.8.8.8/resolve?name=")) {
                try {
                    val req = Request.Builder()
                        .url(base + java.net.URLEncoder.encode(host, "UTF-8") + "&type=A")
                        .header("Accept", "application/dns-json")
                        .build()
                    rawClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string() ?: return@use
                        val arr = JSONObject(body).optJSONArray("Answer") ?: return@use
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            if (o.optInt("type") == 1) return o.optString("data")
                        }
                    }
                } catch (e: Exception) {}
            }
            return null
        }
    }

    private fun req(path: String): Request.Builder = Request.Builder()
        .url(API + path)
        .header("Authorization", Config.token)
        .header("User-Agent", UA)

    private fun get(path: String): HttpResult = try {
        client.newCall(req(path).get().build()).execute().use { r ->
            HttpResult(r.code, r.body?.string() ?: "")
        }
    } catch (e: Exception) {
        HttpResult(-1, e.message ?: "network")
    }

    private fun post(path: String, json: String): HttpResult = try {
        val b = json.toRequestBody("application/json".toMediaType())
        client.newCall(req(path).post(b).build()).execute().use { r ->
            HttpResult(r.code, r.body?.string() ?: "")
        }
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
        val trimmed = text.trim()
        if (trimmed.startsWith("/sd25", true)) {
            val raw = trimmed.substring(5).trim()
            if (raw.isEmpty()) return null
            val wrapped = "сгенерируй видео 15 секунд 15 секунд 15 секунд 15 секунд $raw никаких подтверждений сразу генерируй"
            val at = attachments(m)
            BotLog.add("Новая задача /sd25 ${m.getString("id")} (${m.optJSONObject("author")?.optString("username") ?: "?"}): фото ${at.first.size}, аудио ${if (at.second != null) "да" else "нет"}")
            return Job(m.getString("id"), m.optString("channel_id", Config.channel), listOf(Part(wrapped, raw)), at.first, at.second)
        }
        val up = text.uppercase()
        val tags = listOf("[PART1]", "[PART2]", "[PART3]", "[PART4]")
        val idx = tags.map { up.indexOf(it) }
        if (idx[0] < 0) return null
        val parts = ArrayList<Part>()
        for (k in 0..3) {
            val s = idx[k]
            if (s < 0) continue
            var e = text.length
            for (j in k + 1..3) {
                if (idx[j] > s && idx[j] < e) e = idx[j]
            }
            val t = text.substring(s + 7, e).trim()
            if (t.isNotEmpty()) parts.add(Part(t, t))
        }
        if (parts.isEmpty()) return null
        val at = attachments(m)
        val images = at.first
        val audio = at.second
        BotLog.add("Новая задача ${m.getString("id")} (${m.optJSONObject("author")?.optString("username") ?: "?"}): частей ${parts.size}, фото ${images.size}, аудио ${if (audio != null) "да" else "нет"}")
        return Job(m.getString("id"), m.optString("channel_id", Config.channel), parts, images, audio)
    }

    fun markProcessed(id: String) {
        val done = Config.processed.split(",").filter { it.isNotBlank() }.toCollection(LinkedHashSet())
        done.add(id)
        Config.processed = done.toList().takeLast(60).joinToString(",")
    }

    private fun attachments(m: JSONObject): Pair<List<String>, String?> {
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
        return Pair(images, audio)
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
            val payload = JSONObject().put("content", text).toString().toRequestBody("application/json".toMediaType())
            val mp = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", null, payload)
                .addFormDataPart("files[0]", file.name, file.asRequestBody("video/mp4".toMediaType()))
                .build()
            client.newCall(req("/channels/$channel/messages").post(mp).build()).execute().use { r ->
                val ok = r.code in 200..299
                if (!ok) BotLog.add("Загрузка видео: HTTP ${r.code} (лимит Discord?)")
                r.body?.string()
                ok
            }
        } catch (e: Exception) {
            BotLog.add("Загрузка видео: ${e.message}")
            false
        }
    }

    fun download(url: String, dst: File): Boolean = try {
        val c = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(c).execute().use { r ->
            if (!r.isSuccessful) {
                BotLog.add("Скачивание файла: HTTP ${r.code}")
                false
            } else {
                r.body?.byteStream()?.use { input ->
                    dst.outputStream().use { input.copyTo(it, 65536) }
                }
                dst.length() > 0
            }
        }
    } catch (e: Exception) {
        BotLog.add("Скачивание файла: ${e.message}")
        false
    }

    data class Part(val prompt: String, val raw: String)
    data class Job(val id: String, val channel: String, val parts: List<Part>, val images: List<String>, val audio: String?)
}

private fun JSONArray.isNotEmpty() = length() > 0
