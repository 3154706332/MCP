package com.max.mcp.core

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class McpBridgeRegistry(private val context: android.content.Context, private val settings: SettingsStore) {
    private val bridges = ConcurrentHashMap<String, McpBridge>()

    interface OnBridgeChangeListener {
        fun onBridgeAdded(bridgeId: String)
        fun onBridgeRemoved(bridgeId: String)
        fun onBridgeStateChanged(bridgeId: String, online: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<OnBridgeChangeListener>()
    fun addListener(l: OnBridgeChangeListener) = listeners.add(l)
    fun removeListener(l: OnBridgeChangeListener) = listeners.remove(l)

    data class BridgeConfig(
        val id: String, val name: String, val url: String,
        val token: String? = null, val enabled: Boolean = true,
        val autoConnect: Boolean = true
    )

    data class BridgeState(
        val config: BridgeConfig, val online: Boolean = false,
        val lastError: String = "", val tools: List<McpClient.ToolDef> = emptyList()
    )

    inner class McpBridge(val config: BridgeConfig) {
        private val client = McpClient(baseUrl = config.url, token = config.token)
        fun connect(): BridgeState {
            client.initialize()
            return getState()
        }
        fun healthCheck(): BridgeState {
            client.ping()
            return getState()
        }
        fun getState(): BridgeState {
            val st = client.state()
            return BridgeState(config = config, online = st.online,
                lastError = st.lastError, tools = st.tools)
        }
        fun listTools() = client.listTools()
        fun callTool(name: String, args: JSONObject) = client.callTool(name, args) // stub
    }

    private fun persistBridges() {
        // simplified persistence
    }
    private fun loadFromSettings() {}
    private fun notifyAdded(id: String) = listeners.forEach { it.onBridgeAdded(id) }
    private fun notifyRemoved(id: String) = listeners.forEach { it.onBridgeRemoved(id) }
    private fun notifyStateChanged(id: String, online: Boolean) = listeners.forEach { it.onBridgeStateChanged(id, online) }

    fun getBridge(id: String): McpBridge? = bridges[id]
    fun getAllBridges(): List<McpBridge> = bridges.values.toList()
    fun getEnabledBridges(): List<McpBridge> = bridges.values.filter { it.config.enabled }

    fun addBridge(config: BridgeConfig): McpBridge {
        val bridge = McpBridge(config)
        bridges[config.id] = bridge
        persistBridges()
        notifyAdded(config.id)
        if (config.autoConnect) bridge.connect()
        return bridge
    }

    fun removeBridge(id: String): Boolean {
        val removed = bridges.remove(id) != null
        if (removed) notifyRemoved(id)
        return removed
    }
}