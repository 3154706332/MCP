package com.soreverse.mcp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registry for managing multiple MCP server connections (bridges).
 * Each bridge connects to a separate MCP server and exposes its tools under a namespace.
 * 
 * Supports:
 * - MT Manager APK MCP (mt_apk_* tools)
 * - Custom local MCP servers (user-defined)
 * - Remote MCP servers via HTTP/SSE
 */
class McpBridgeRegistry(private val context: Context, private val settings: SettingsStore) {

    private val bridges = ConcurrentHashMap<String, McpBridge>()
    private val listeners = CopyOnWriteArrayList<OnBridgeChangeListener>()

    interface OnBridgeChangeListener {
        fun onBridgeAdded(bridgeId: String)
        fun onBridgeRemoved(bridgeId: String)
        fun onBridgeStateChanged(bridgeId: String, online: Boolean)
        fun onBridgeToolsChanged(bridgeId: String)
    }

    fun addListener(listener: OnBridgeChangeListener) = listeners.add(listener)
    fun removeListener(listener: OnBridgeChangeListener) = listeners.remove(listener)

    private fun notifyAdded(id: String) = listeners.forEach { it.onBridgeAdded(id) }
    private fun notifyRemoved(id: String) = listeners.forEach { it.onBridgeRemoved(id) }
    private fun notifyStateChanged(id: String, online: Boolean) = listeners.forEach { it.onBridgeStateChanged(id, online) }
    private fun notifyToolsChanged(id: String) = listeners.forEach { it.onBridgeToolsChanged(id) }

    data class BridgeConfig(
        val id: String,
        val name: String,
        val url: String,
        val token: String? = null,
        val namespace: String = "ext",
        val enabled: Boolean = true,
        val autoConnect: Boolean = true,
        val toolNamePrefix: String? = null,  // override default prefix (namespace_)
        val connectTimeoutSec: Int = 10,
        val readTimeoutSec: Int = 30,
    ) {
        fun effectivePrefix(): String = toolNamePrefix ?: "${namespace}_"
        fun toolNamespace(): String = namespace
    }

    data class BridgeState(
        val config: BridgeConfig,
        val online: Boolean = false,
        val lastError: String = "",
        val tools: List<McpClient.ToolDef> = emptyList(),
        val serverInfo: McpClient.ServerInfo? = null,
        val lastCheckedAt: Long = 0,
        val lastLatencyMs: Long = 0,
        val probes: Long = 0,
        val probeFailures: Long = 0,
    )

    /**
     * Add or update a bridge configuration. Persists to settings.
     */
    @Synchronized
    fun addBridge(config: BridgeConfig): McpBridge {
        val existing = bridges[config.id]
        val bridge = McpBridge(config, settings)
        bridges[config.id] = bridge
        persistBridges()
        if (existing == null) {
            notifyAdded(config.id)
            if (config.autoConnect) {
                bridge.connect()
            }
        } else {
            notifyStateChanged(config.id, bridge.client.state().online)
            notifyToolsChanged(config.id)
        }
        return bridge
    }

    /**
     * Remove a bridge by ID.
     */
    @Synchronized
    fun removeBridge(bridgeId: String): Boolean {
        val removed = bridges.remove(bridgeId) != null
        if (removed) {
            persistBridges()
            notifyRemoved(bridgeId)
        }
        return removed
    }

    /**
     * Get bridge by ID.
     */
    fun getBridge(bridgeId: String): McpBridge? = bridges[bridgeId]

    /**
     * Get all bridges.
     */
    fun getAllBridges(): List<McpBridge> = bridges.values.toList()

    /**
     * Get all enabled bridges.
     */
    fun getEnabledBridges(): List<McpBridge> = bridges.values.filter { it.config.enabled }.toList()

    /**
     * Get merged tool definitions from all online bridges.
     */
    fun getMergedTools(): List<MergedToolDef> {
        val out = ArrayList<MergedToolDef>()
        bridges.values.filter { it.config.enabled }.forEach { bridge ->
            val state = bridge.getState()
            if (state.online) {
                state.tools.forEach { tool ->
                    out.add(MergedToolDef(
                        originalName = tool.name,
                        prefixedName = "${bridge.config.effectivePrefix()}${tool.name}",
                        bridgeId = bridge.config.id,
                        bridgeName = bridge.config.name,
                        namespace = bridge.config.toolNamespace(),
                        title = tool.title,
                        description = tool.description,
                        inputSchema = tool.inputSchema,
                        outputSchema = tool.outputSchema,
                    ))
                }
            }
        }
        return out
    }

    /**
     * Check if a tool name belongs to a bridged server.
     */
    fun isBridgedTool(toolName: String): Boolean {
        return bridges.values.any { bridge ->
            bridge.config.enabled && toolName.startsWith(bridge.config.effectivePrefix())
        }
    }

    /**
     * Find the bridge that owns a tool.
     */
    fun findBridgeForTool(toolName: String): McpBridge? {
        return bridges.values.find { bridge ->
            bridge.config.enabled && toolName.startsWith(bridge.config.effectivePrefix())
        }
    }

    /**
     * Call a tool on the appropriate bridge.
     */
    fun callBridgedTool(toolName: String, arguments: JSONObject): JSONObject? {
        val bridge = findBridgeForTool(toolName)
        return bridge?.callTool(stripPrefix(toolName, bridge.config.effectivePrefix()), arguments)
    }

    /**
     * Strip the namespace prefix from a tool name.
     */
    internal fun stripPrefix(name: String, prefix: String): String {
        return if (name.startsWith(prefix)) name.substring(prefix.length) else name
    }

    /**
     * Trigger health check on all enabled bridges.
     */
    fun healthCheckAll() {
        bridges.values.filter { it.config.enabled }.forEach { it.healthCheck() }
    }

    /**
     * Load bridges from settings.
     */
    fun loadFromSettings() {
        val json = settings.mcpBridgesJson
        if (json.isBlank()) {
            // Migrate from old ApkMcpBridge settings
            migrateFromApkMcpBridge()
            return
        }
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val cfg = arr.getJSONObject(i)
                val config = BridgeConfig(
                    id = cfg.optString("id", ""),
                    name = cfg.optString("name", ""),
                    url = cfg.optString("url", ""),
                    token = cfg.optString("token").takeIf { it.isNotBlank() },
                    namespace = cfg.optString("namespace", "ext"),
                    enabled = cfg.optBoolean("enabled", true),
                    autoConnect = cfg.optBoolean("autoConnect", true),
                    toolNamePrefix = cfg.optString("toolNamePrefix").takeIf { it.isNotBlank() },
                    connectTimeoutSec = cfg.optInt("connectTimeoutSec", 10),
                    readTimeoutSec = cfg.optInt("readTimeoutSec", 30),
                )
                val bridge = McpBridge(config, settings)
                bridges[config.id] = bridge
                if (config.autoConnect) {
                    bridge.connect()
                }
            }
        } catch (e: Exception) {
            AppLog.e("Failed to load MCP bridges from settings", e)
        }
    }

    private fun migrateFromApkMcpBridge() {
        val oldUrl = settings.apkMcpUrl
        val oldToken = settings.apkMcpToken
        if (oldUrl.isNotBlank()) {
            val config = BridgeConfig(
                id = "mt_manager_apk",
                name = "MT Manager APK MCP",
                url = oldUrl,
                token = oldToken,
                namespace = "mt_apk",
                enabled = true,
                autoConnect = settings.apkMcpAutoProbe,
            )
            addBridge(config)
            AppLog.i("Migrated legacy APK MCP bridge to new registry")
        }
    }

    private fun persistBridges() {
        val arr = JSONArray()
        bridges.values.forEach { bridge ->
            val cfg = bridge.config
            arr.put(JSONObject()
                .put("id", cfg.id)
                .put("name", cfg.name)
                .put("url", cfg.url)
                .put("token", cfg.token ?: "")
                .put("namespace", cfg.namespace)
                .put("enabled", cfg.enabled)
                .put("autoConnect", cfg.autoConnect)
                .put("toolNamePrefix", cfg.toolNamePrefix ?: "")
                .put("connectTimeoutSec", cfg.connectTimeoutSec)
                .put("readTimeoutSec", cfg.readTimeoutSec))
        }
        settings.mcpBridgesJson = arr.toString()
    }

    /**
     * Bridge wrapper that manages connection lifecycle.
     */
    inner class McpBridge(
        val config: BridgeConfig,
        private val settings: SettingsStore
    ) {
        internal val client = McpClient(
            baseUrl = config.url,
            token = config.token,
            connectTimeoutSec = config.connectTimeoutSec,
            readTimeoutSec = config.readTimeoutSec
        )

        @Volatile private var healthThread: Thread? = null
        @Volatile private var healthStop = false

        fun connect(): BridgeState {
            client.initialize()
            return getState()
        }

        fun healthCheck(): BridgeState {
            client.ping()
            return getState()
        }

        fun listTools(): List<McpClient.ToolDef> {
            return client.listTools()
        }

        fun callTool(name: String, arguments: JSONObject): JSONObject {
            return client.callTool(name, arguments)
        }

        fun getState(): BridgeState {
            val st = client.state()
            return BridgeState(
                config = config,
                online = st.online,
                lastError = st.lastError,
                tools = st.tools,
                serverInfo = st.serverInfo,
                lastCheckedAt = st.lastCheckedAt,
                lastLatencyMs = st.lastLatencyMs,
                probes = st.probes,
                probeFailures = st.probeFailures,
            )
        }

        fun startHealthMonitor(intervalMs: Long = 30_000) {
            stopHealthMonitor()
            healthStop = false
            healthThread = Thread({
                while (!healthStop && !Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(intervalMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (healthStop) break
                    if (!config.enabled) continue
                    val prevState = getState()
                    val newState = client.ping()
                    if (newState.online != prevState.online) {
                        notifyStateChanged(config.id, newState.online)
                    }
                    if (newState.online && newState.tools != prevState.tools) {
                        notifyToolsChanged(config.id)
                    }
                }
            }, "mcp-bridge-health-${config.id}").apply { isDaemon = true; start() }
        }

        fun stopHealthMonitor() {
            healthStop = true
            healthThread?.interrupt()
            healthThread = null
        }

        fun snapshotJson(): JSONObject {
            return client.snapshotJson().apply {
                put("id", config.id)
                put("name", config.name)
                put("namespace", config.namespace)
                put("enabled", config.enabled)
                put("autoConnect", config.autoConnect)
                put("toolPrefix", config.effectivePrefix())
            }
        }
    }

    data class MergedToolDef(
        val originalName: String,
        val prefixedName: String,
        val bridgeId: String,
        val bridgeName: String,
        val namespace: String,
        val title: String?,
        val description: String?,
        val inputSchema: JSONObject?,
        val outputSchema: JSONObject?,
    )
}