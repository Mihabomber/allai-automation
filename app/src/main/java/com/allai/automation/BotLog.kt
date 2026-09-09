package com.allai.automation

import android.text.format.DateFormat
import java.util.ArrayDeque

object BotLog {
    private val lines = ArrayDeque<String>()
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized fun add(msg: String) {
        lines.addLast("[${DateFormat.format("HH:mm:ss", System.currentTimeMillis())}] $msg")
        while (lines.size > 200) lines.removeFirst()
        listener?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
    }

    @Synchronized fun text(): String = lines.joinToString("\n")
}
