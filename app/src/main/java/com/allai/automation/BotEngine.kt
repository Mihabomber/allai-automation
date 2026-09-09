package com.allai.automation

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object BotEngine {
    @Volatile var running = false
    private var t: Thread? = null

    fun start() {
        if (running) return
        running = true
        DiscordPoller.stopRequested = false
        DolaAutomationService.stopRequested = false
        t = thread(name = "allai-engine") {
            BotLog.add("Бот запущен (интервал ${Config.pollSec}с)")
            while (running && !DiscordPoller.stopRequested) {
                try {
                    val svc = DolaAutomationService.instance
                    if (svc == null) {
                        BotLog.add("Включи спец. возможности!")
                        Thread.sleep(10_000)
                        continue
                    }
                    val job = DiscordPoller.nextJob()
                    if (job == null) {
                        Thread.sleep(Config.pollSec * 1000L)
                        continue
                    }
                    val latch = CountDownLatch(1)
                    var ok = false
                    var files: List<File> = emptyList()
                    var err = ""
                    svc.runJob(job) { o, f, e ->
                        ok = o; files = f; err = e; latch.countDown()
                    }
                    BotState.step = "РАБОТАЮ: задача ${job.id}"
                    latch.await(45, TimeUnit.MINUTES)
                    BotState.step = "ОЖИДАНИЕ"
                    if (files.isNotEmpty()) {
                        val names = files.joinToString(", ") { it.name }
                        var sent = DiscordPoller.sendVideo(job.channel, "✅ Готово: $names", files[0])
                        for (i in 1 until files.size) {
                            sent = DiscordPoller.sendVideo(job.channel, "Файл ${i + 1}", files[i]) || sent
                        }
                        if (!sent) BotLog.add("Не смог отправить видео в Discord")
                    } else {
                        DiscordPoller.send(job.channel, "❌ Ошибка: $err")
                    }
                } catch (e: Exception) {
                    BotLog.add("Цикл: ${e.message}")
                    Thread.sleep(5_000)
                }
            }
            running = false
            BotLog.add("Бот остановлен")
        }
    }

    fun stop() {
        running = false
        DiscordPoller.stopRequested = true
        DolaAutomationService.stopRequested = true
    }
}
