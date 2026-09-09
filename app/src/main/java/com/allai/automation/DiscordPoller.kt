package com.allai.automation

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object DiscordPoller {
    @Volatile var stopRequested = false
    private const val API = "https://discord.com/api/v10"

    fun nextJob(): Job? {
        if (!Config.ready()) return null
        try {
            val c = URL("$API/channels/${Config.channel}/messages?limit=10&after=${Config.lastMsgId}").openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", Config.token)
            c.connectTimeout = 10000
            c.readTimeout = 15000
            if (c.responseCode != 200) {
                BotLog.add("Discord: HTTP ${c.responseCode} — проверь токен/ID канала")
                c.errorStream?.close()
                return null
            }
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            c.disconnect()
            var newest = Config.lastMsgId
            var job: Job? = null
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val id = m.getString("id")
                if (newest == "0" || id.toLong() > newest.toLong()) newest = id
                if (job == null) job = parse(m)
            }
            Config.lastMsgId = newest
            return job
        } catch (e: Exception) {
            BotLog.add("Discord: ошибка сети ${e.message}")
            return null
        }
    }

    private fun parse(m: JSONObject): Job? {
        val text = m.optString("content")
        if (!text.contains("[PART1]")) return null
        val p1 = text.substringAfter("[PART1]").substringBefore("[PART2]").trim()
        val p2 = if (text.contains("[PART2]")) text.substringAfter("[PART2]").substringBefore("[PART3]").trim() else ""
        if (p1.isEmpty()) return null
        var logo: String? = null
        val att = m.optJSONArray("attachments")
        if (att != null && att.length() > 0) {
            val u = att.getJSONObject(0).optString("url")
            if (u.endsWith(".png") || u.endsWith(".jpg") || u.endsWith(".jpeg")) logo = u
        }
        BotLog.add("Новая задача: ${m.getString("id")}")
        return Job(m.getString("id"), m.optString("channel_id", Config.channel), p1, p2, logo)
    }

    fun send(channel: String, text: String): Boolean {
        return try {
            val c = URL("$API/channels/$channel/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Authorization", Config.token)
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.write(JSONObject().put("content", text).toString().toByteArray())
            val ok = c.responseCode in 200..299
            c.disconnect()
            ok
        } catch (e: Exception) {
            BotLog.add("Discord send: ${e.message}")
            false
        }
    }

    fun sendVideo(channel: String, text: String, file: File): Boolean {
        return try {
            val boundary = "----allai${System.currentTimeMillis()}"
            val c = URL("$API/channels/$channel/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Authorization", Config.token)
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
        c.connectTimeout = 15000
        dst.outputStream().use { o -> c.inputStream.use { it.copyTo(o, 65536) } }
        c.disconnect()
        true
    } catch (e: Exception) {
        BotLog.add("Скачивание файла: ${e.message}")
        false
    }

    data class Job(val id: String, val channel: String, val part1: String, val part2: String, val logoUrl: String?)
}
