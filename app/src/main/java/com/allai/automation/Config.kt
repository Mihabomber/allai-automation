package com.allai.automation

import android.content.Context
import android.content.SharedPreferences

object Config {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) {
        sp = c.getSharedPreferences("allai", Context.MODE_PRIVATE)
    }
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()
    var channel: String
        get() = sp.getString("channel", "") ?: ""
        set(v) = sp.edit().putString("channel", v.trim()).apply()
    var dolaUrl: String
        get() = sp.getString("dolaUrl", "https://www.dola.com/chat/") ?: "https://www.dola.com/chat/"
        set(v) = sp.edit().putString("dolaUrl", v.trim()).apply()
    var dolaPkg: String
        get() = sp.getString("dolaPkg", "") ?: ""
        set(v) = sp.edit().putString("dolaPkg", v.trim()).apply()
    var pollSec: Int
        get() = sp.getInt("pollSec", 10)
        set(v) = sp.edit().putInt("pollSec", v).apply()
    var lastMsgId: String
        get() = sp.getString("lastMsgId", "0") ?: "0"
        set(v) = sp.edit().putString("lastMsgId", v).apply()
    var processed: String
        get() = sp.getString("processed", "") ?: ""
        set(v) = sp.edit().putString("processed", v).apply()
    var day: String
        get() = sp.getString("day", "") ?: ""
        set(v) = sp.edit().putString("day", v).apply()
    var dayJobs: Int
        get() = sp.getInt("dayJobs", 0)
        set(v) = sp.edit().putInt("dayJobs", v).apply()
    var dayParts: Int
        get() = sp.getInt("dayParts", 0)
        set(v) = sp.edit().putInt("dayParts", v).apply()
    fun rollDay() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (day != today) {
            day = today
            dayJobs = 0
            dayParts = 0
        }
    }
    fun ready(): Boolean = token.isNotEmpty() && channel.isNotEmpty()
}
