package com.max.mcp.core

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mcp_lite", Context.MODE_PRIVATE)

    var mcpPort: Int
        get() = prefs.getInt("mcpPort", 8000)
        set(v) = prefs.edit().putInt("mcpPort", v).apply()

    var tunnelMode: String
        get() = prefs.getString("tunnelMode", "quick") ?: "quick"
        set(v) = prefs.edit().putString("tunnelMode", v).apply()

    var tunnelTargetPort: Int
        get() = prefs.getInt("tunnelTargetPort", 8000)
        set(v) = prefs.edit().putInt("tunnelTargetPort", v).apply()

    var tunnelAutoStart: Boolean
        get() = prefs.getBoolean("tunnelAutoStart", false)
        set(v) = prefs.edit().putBoolean("tunnelAutoStart", v).apply()

    var tunnelToken: String
        get() = prefs.getString("tunnelToken", "") ?: ""
        set(v) = prefs.edit().putString("tunnelToken", v).apply()

    var apkMcpUrl: String
        get() = prefs.getString("apkMcpUrl", "") ?: ""
        set(v) = prefs.edit().putString("apkMcpUrl", v).apply()

    var apkMcpAutoProbe: Boolean
        get() = prefs.getBoolean("apkMcpAutoProbe", false)
        set(v) = prefs.edit().putBoolean("apkMcpAutoProbe", v).apply()

    var bootAutoStart: Boolean
        get() = prefs.getBoolean("bootAutoStart", false)
        set(v) = prefs.edit().putBoolean("bootAutoStart", v).apply()

    var languageZh: Boolean
        get() = prefs.getBoolean("languageZh", false)
        set(v) = prefs.edit().putBoolean("languageZh", v).apply()

    fun snapshot(): Map<String, Any?> = prefs.all
}