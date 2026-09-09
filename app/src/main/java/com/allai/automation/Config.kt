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
        get() = sp.getString("dolaUrl", "https://www.dolai.video/") ?: "https://www.dolai.video/"
        set(v) = sp.edit().putString("dolaUrl", v.trim()).apply()
    var pollSec: Int
        get() = sp.getInt("pollSec", 10)
        set(v) = sp.edit().putInt("pollSec", v).apply()
    var lastMsgId: String
        get() = sp.getString("lastMsgId", "0") ?: "0"
        set(v) = sp.edit().putString("lastMsgId", v).apply()
    fun ready(): Boolean = token.isNotEmpty() && channel.isNotEmpty()
}
