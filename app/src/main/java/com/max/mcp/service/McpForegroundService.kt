package com.max.mcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.max.mcp.R
import com.max.mcp.core.AppLog
import com.max.mcp.core.SettingsStore
import com.max.mcp.mcp.McpHttpServer

class McpForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        currentServer?.stop()
        super.onDestroy()
    }

    private fun startServer() {
        try {
            val settings = SettingsStore(applicationContext)
            val server = McpHttpServer(applicationContext, settings.mcpPort, "0.0.0.0")
            server.start()
            currentServer = server
            startForeground(NOTIFICATION_ID, notification("Running on port \${settings.mcpPort}"))
        } catch (e: Exception) {
            AppLog.e("Failed to start server", e)
        }
    }

    private fun stopServer() {
        try {
            currentServer?.stop()
            currentServer = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            AppLog.e("Failed to stop server", e)
        }
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            createChannel()
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("MCP Lite")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_somcp)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "MCP Lite", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val ACTION_START = "com.max.mcp.START"
        const val ACTION_STOP = "com.max.mcp.STOP"
        private const val CHANNEL_ID = "mcp_lite_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile var currentServer: McpHttpServer? = null; private set
        fun isRunning(): Boolean = currentServer != null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, McpForegroundService::class.java).apply { action = ACTION_START })
        }
        fun stop(context: Context) {
            context.startService(Intent(context, McpForegroundService::class.java).apply { action = ACTION_STOP })
        }
    }
}
