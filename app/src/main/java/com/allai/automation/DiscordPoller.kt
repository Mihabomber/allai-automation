package com.allai.automation

// Polling Discord DM: GET /channels/{dmId}/messages?after={lastId}
// Заголовок Authorization: <user/bot token>. Парсит PART1 / PART2 / logo url.
object DiscordPoller {
    @Volatile var stopRequested = false
    data class Job(val part1: String, val part2: String, val logoUrl: String?)
    fun parseMessage(text: String): Job? {
        // Ожидаем: [PART1]...[PART2]...  + аттач png
        if (!text.contains("[PART1]") || !text.contains("[PART2]")) return null
        val p1 = text.substringAfter("[PART1]").substringBefore("[PART2]").trim()
        val p2 = text.substringAfter("[PART2]").trim()
        return Job(p1, p2, null)
    }
    // При ошибке OCR в Dola: mutate(prompt) — синонимы/перестановка слов, повтор.
    fun mutateOnError(prompt: String): String = prompt + " " // TODO: реальная мутация
}
