package com.soreverse.mcp

import android.content.Context
import com.soreverse.mcp.core.McpBridgeRegistry
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.mcp.McpHttpServer
import com.soreverse.mcp.service.McpForegroundService

internal fun activeServer(context: Context): McpHttpServer? =
    McpForegroundService.currentServer

/**
 * Get the legacy APK MCP bridge (MT Manager) from the bridge registry.
 * This maintains backward compatibility while migrating to the new multi-bridge system.
 */
internal fun activeLegacyApkBridge(context: Context): McpBridgeRegistry.McpBridge? =
    activeServer(context)?.bridgeRegistry?.getBridge("mt_manager_apk")
