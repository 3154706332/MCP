package com.max.mcp

internal enum class MainTab { Service, Bridges, Settings }
internal enum class SettingsDest { Root, Tunnel, BridgeRegistry, ServiceConfig }

internal data class UiText(
    val zh: Boolean = false,
    val appTitle: String = "MCP Lite",
    val state: String = "Status",
    val running: String = "Running",
    val stopped: String = "Stopped",
    val settings: String = "Settings",
    val tunnel: String = "Tunnel",
    val bridges: String = "Bridges",
    val service: String = "Service",
    val serverPort: String = "Server Port",
    val tunnelMode: String = "Tunnel Mode",
    val token: String = "Token",
    val autoStart: String = "Auto Start",
)
