package com.max.mcp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.max.mcp.core.AppLog
import com.max.mcp.core.SettingsStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (SettingsStore(context).bootAutoStart) {
                McpForegroundService.start(context)
                AppLog.i("Auto-started via boot")
            }
        }
    }
}
