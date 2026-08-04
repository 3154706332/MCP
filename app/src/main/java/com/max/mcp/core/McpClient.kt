package com.max.mcp.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class McpClient(
    private val baseUrl: String,
    private val token: String? = null,
    private val connectTimeoutSec: Int = 10,
    private val readTimeoutSec: Int = 30
) {
    data class ToolDef(val name: String, val description: String = "")
    data class State(
        val url: String,
        val online: Boolean = false,
        val lastError: String = "",
        val tools: List<ToolDef> = emptyList(),
        val lastLatencyMs: Long = 0,
        val probes: Long = 0,
        val probeFailures: Long = 0,
    )

    private val _state = AtomicReference(State(url = baseUrl))
    fun state(): State = _state.get()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec.toLong(), TimeUnit.SECONDS)
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
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder.build()
    }

    @Synchronized
    fun initialize(): State {
        return try {
            val req = buildRequest("initialize", JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("client", JSONObject().put("name", "MCPLite").put("version", "1.0.0"))
                .put("capabilities", JSONObject()))
            val start = System.nanoTime()
            val resp = client.newCall(req).execute()
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            val s = _state.get().copy(online = true, lastLatencyMs = latencyMs,
                probes = _state.get().probes + 1)
            _state.set(s)
            s
        } catch (e: Exception) {
            val s = _state.get().copy(online = false, lastError = e.message ?: e.javaClass.simpleName,
                probes = _state.get().probes + 1, probeFailures = _state.get().probeFailures + 1)
            _state.set(s)
            s
        }
    }

    @Synchronized
    fun listTools(): List<ToolDef> {
        val st = _state.get()
        if (!st.online) return emptyList()
        return try {
            val req = buildRequest("tools/list", JSONObject())
            val resp = client.newCall(req).execute()
            val root = JSONObject(resp.body?.string() ?: "{}")
            val result = root.optJSONObject("result") ?: return emptyList()
            val tools = result.optJSONArray("tools") ?: return emptyList()
            val out = mutableListOf<ToolDef>()
            for (i in 0 until tools.length()) {
                val t = tools.getJSONObject(i)
                out.add(ToolDef(name = t.optString("name"), description = t.optString("description")))
            }
            val s = _state.get().copy(tools = out, lastError = "")
            _state.set(s)
            out
        } catch (e: Exception) {
            val s = _state.get().copy(online = false, lastError = e.message ?: e.javaClass.simpleName)
            _state.set(s)
            emptyList()
        }
    }

    @Synchronized
    fun ping(): State {
        return try {
            val req = buildRequest("ping", JSONObject())
            val start = System.nanoTime()
            client.newCall(req).execute()
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            val prev = _state.get()
            val s = prev.copy(lastLatencyMs = latencyMs, probes = prev.probes + 1)
            _state.set(s)
            s
        } catch (e: Exception) {
            val s = _state.get().copy(online = false, lastError = e.message ?: e.javaClass.simpleName,
                probes = _state.get().probes + 1, probeFailures = _state.get().probeFailures + 1)
            _state.set(s)
            s
        }
    }
}