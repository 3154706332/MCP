package com.soreverse.mcp.core

import com.soreverse.mcp.core.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Generic MCP client for connecting to any MCP server (local or remote).
 * Supports JSON-RPC 2.0 over HTTP and SSE transports.
 */
class McpClient(
    private val baseUrl: String,
    private val token: String? = null,
    private val connectTimeoutSec: Int = 10,
    private val readTimeoutSec: Int = 30
) {

    data class ToolDef(
        val name: String,
        val title: String?,
        val description: String?,
        val inputSchema: JSONObject?,
        val outputSchema: JSONObject?,
    )

    data class ServerInfo(
        val name: String,
        val version: String,
        val protocolVersion: String,
        val capabilities: JSONObject,
    )

    data class State(
        val url: String,
        val online: Boolean = false,
        val lastError: String = "",
        val tools: List<ToolDef> = emptyList(),
        val serverInfo: ServerInfo? = null,
        val lastCheckedAt: Long = 0,
        val lastLatencyMs: Long = 0,
        val probes: Long = 0,
        val probeFailures: Long = 0,
        val totalLatencyMs: Long = 0,
        val maxLatencyMs: Long = 0,
    ) {
        fun avgLatencyMs(): Long = if (probes > 0) totalLatencyMs / probes else 0
        fun lossRate(): Double = if (probes == 0L) 0.0 else probeFailures.toDouble() / probes
    }

    private val _state = AtomicReference(State(url = baseUrl))
    fun state(): State = _state.get()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private var idCounter = 1000
    private fun nextId(): Int = idCounter++

    private fun buildRequest(method: String, params: JSONObject): Request {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId())
            .put("method", method)
            .put("params", params)
            .toString()

        val builder = Request.Builder()
            .url(baseUrl)
            .post(body.toRequestBody("application/json".toMediaType()))

        token?.ifNotBlank { builder.safeHeader("Authorization", "Bearer $it") }
        return builder.build()
    }

    private fun post(req: Request): String {
        client.newCall(req).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: $body")
            return body
        }
    }

    @Synchronized
    fun initialize(): State {
        try {
            val req = buildRequest("initialize", JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("client", JSONObject().put("name", "SOMCP-MCP-Client").put("version", "1.0.0"))
                .put("capabilities", JSONObject()))
            val start = System.nanoTime()
            val resp = post(req)
            val latencyMs = (System.nanoTime() - start) / 1_000_000

            val root = JSONObject(resp)
            val result = root.optJSONObject("result")
            val serverInfo = result?.let {
                ServerInfo(
                    name = it.optString("serverInfo.name", "unknown"),
                    version = it.optString("serverInfo.version", "unknown"),
                    protocolVersion = it.optString("protocolVersion", "unknown"),
                    capabilities = it.optJSONObject("capabilities") ?: JSONObject(),
                )
            }

            val s = _state.get().copy(
                online = true,
                lastError = "",
                serverInfo = serverInfo,
                lastCheckedAt = System.currentTimeMillis(),
                lastLatencyMs = latencyMs,
                probes = _state.get().probes + 1,
                totalLatencyMs = _state.get().totalLatencyMs + latencyMs,
                maxLatencyMs = maxOf(_state.get().maxLatencyMs, latencyMs)
            )
            _state.set(s)
            AppLog.i("mcp-client: initialized $baseUrl (${serverInfo?.name} v${serverInfo?.version})")
            return s
        } catch (e: Exception) {
            val prev = _state.get()
            val s = prev.copy(
                online = false,
                lastError = e.message ?: e.javaClass.simpleName,
                probes = prev.probes + 1,
                probeFailures = prev.probeFailures + 1
            )
            _state.set(s)
            AppLog.w("mcp-client initialize failed: ${e.message}")
            return s
        }
    }

    @Synchronized
    fun listTools(): List<ToolDef> {
        val st = _state.get()
        if (!st.online) return emptyList()

        try {
            val req = buildRequest("tools/list", JSONObject())
            val start = System.nanoTime()
            val resp = post(req)
            val latencyMs = (System.nanoTime() - start) / 1_000_000

            val tools = parseTools(resp)
            val prev = _state.get()
            val s = prev.copy(
                online = true,
                lastError = "",
                tools = tools,
                lastCheckedAt = System.currentTimeMillis(),
                lastLatencyMs = latencyMs,
                probes = prev.probes + 1,
                totalLatencyMs = prev.totalLatencyMs + latencyMs,
                maxLatencyMs = maxOf(prev.maxLatencyMs, latencyMs)
            )
            _state.set(s)
            AppLog.i("mcp-client: listed ${tools.size} tools from $baseUrl")
            return tools
        } catch (e: Exception) {
            val prev = _state.get()
            val s = prev.copy(
                online = false,
                lastError = e.message ?: e.javaClass.simpleName,
                probes = prev.probes + 1,
                probeFailures = prev.probeFailures + 1
            )
            _state.set(s)
            AppLog.w("mcp-client listTools failed: ${e.message}")
            return emptyList()
        }
    }

    @Synchronized
    fun callTool(name: String, arguments: JSONObject): JSONObject {
        val st = _state.get()
        if (!st.online) {
            return errorResult(name, "MCP server is offline")
        }

        try {
            val params = JSONObject().put("name", name).put("arguments", arguments)
            val req = buildRequest("tools/call", params)
            val resp = post(req)
            return parseToolResult(resp)
        } catch (e: Exception) {
            return errorResult(name, "forward failed: ${e.message}")
        }
    }

    @Synchronized
    fun ping(): State {
        try {
            val req = buildRequest("ping", JSONObject())
            val start = System.nanoTime()
            post(req)
            val latencyMs = (System.nanoTime() - start) / 1_000_000

            val prev = _state.get()
            val s = if (!prev.online) {
                prev.copy(lastLatencyMs = latencyMs, lastCheckedAt = System.currentTimeMillis(), probes = prev.probes + 1)
            } else {
                State(
                    url = baseUrl,
                    online = true,
                    lastError = "",
                    tools = prev.tools,
                    serverInfo = prev.serverInfo,
                    lastCheckedAt = System.currentTimeMillis(),
                    lastLatencyMs = latencyMs,
                    probes = prev.probes + 1,
                    probeFailures = prev.probeFailures,
                    totalLatencyMs = prev.totalLatencyMs + latencyMs,
                    maxLatencyMs = maxOf(prev.maxLatencyMs, latencyMs)
                )
            }
            _state.set(s)
            return s
        } catch (e: Exception) {
            val prev = _state.get()
            val s = prev.copy(
                online = false,
                lastError = e.message ?: e.javaClass.simpleName,
                probes = prev.probes + 1,
                probeFailures = prev.probeFailures + 1
            )
            _state.set(s)
            return s
        }
    }

    fun snapshotJson(): JSONObject {
        val st = _state.get()
        return JSONObject().apply {
            put("url", st.url)
            put("online", st.online)
            put("toolCount", st.tools.size)
            put("lastError", st.lastError)
            put("lastCheckedAt", st.lastCheckedAt)
            put("lastLatencyMs", st.lastLatencyMs)
            put("avgLatencyMs", st.avgLatencyMs())
            put("maxLatencyMs", st.maxLatencyMs)
            put("probes", st.probes)
            put("probeFailures", st.probeFailures)
            put("lossRate", st.lossRate())
            put("tools", JSONArray().apply { st.tools.forEach { put(it.name) } })
            st.serverInfo?.let {
                put("serverInfo", JSONObject()
                    .put("name", it.name)
                    .put("version", it.version)
                    .put("protocolVersion", it.protocolVersion)
                    .put("capabilities", it.capabilities))
            }
        }
    }

    private fun parseTools(body: String): List<ToolDef> {
        val root = JSONObject(body)
        val result = root.opt("result") as? JSONObject ?: return emptyList()
        val tools = result.optJSONArray("tools") ?: return emptyList()
        val out = ArrayList<ToolDef>(tools.length())
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            out.add(ToolDef(
                name = t.optString("name"),
                title = t.optString("title").takeIf { it.isNotBlank() },
                description = t.optString("description").takeIf { it.isNotBlank() },
                inputSchema = t.optJSONObject("inputSchema"),
                outputSchema = t.optJSONObject("outputSchema"),
            ))
        }
        return out
    }

    private fun parseToolResult(body: String): JSONObject {
        val root = JSONObject(body)
        val result = root.opt("result")
        return (result as? JSONObject) ?: JSONObject().put("raw", body)
    }

    private fun errorResult(name: String, msg: String): JSONObject {
        return JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "MCP error [$name]: $msg")))
            .put("isError", true)
            .put("source", "mcp-client")
    }
}

private fun String?.ifNotBlank(block: (String) -> Unit) {
    if (this != null && isNotBlank()) block(this)
}