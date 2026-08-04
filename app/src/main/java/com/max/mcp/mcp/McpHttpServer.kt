package com.max.mcp.mcp

import android.content.Context
import com.max.mcp.core.AppLog
import com.max.mcp.core.CloudflareTunnelManager
import com.max.mcp.core.McpBridgeRegistry
import com.max.mcp.core.SettingsStore
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class McpHttpServer(private val context: Context, private val port: Int, private val host: String) {
    private var engine: EmbeddedServer<*, *>? = null
    private val startedAt = System.currentTimeMillis()

    val tunnel by lazy { CloudflareTunnelManager(context, SettingsStore(context)) }
    val bridgeRegistry by lazy { McpBridgeRegistry(context, SettingsStore(context)) }

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, host = host, port = port) {
            routing {
                get("/health") {
                    call.respondText(
                        JSONObject().put("ok", true).put("server", "MCPLite").toString(),
                        ContentType.Application.Json
                    )
                }
                post("/mcp") {
                    val body = call.receiveText()
                    val response = handleJsonRpc(body)
                    call.respondText(response.toString(), ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        AppLog.i("MCP server listening on $host:$port")
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 400, timeoutMillis = 1500)
        engine = null
    }

    private fun handleJsonRpc(body: String): Any {
        return try {
            val req = JSONObject(body)
            val method = req.optString("method")
            when (method) {
                "initialize" -> JSONObject()
                    .put("jsonrpc", "2.0").put("id", req.optInt("id"))
                    .put("result", JSONObject()
                        .put("protocolVersion", "2025-06-18")
                        .put("serverInfo", JSONObject()
                            .put("name", "MCPLite").put("version", "1.0.0"))
                        .put("capabilities", JSONObject()))
                "ping" -> JSONObject()
                    .put("jsonrpc", "2.0").put("id", req.optInt("id"))
                    .put("result", JSONObject())
                else -> JSONObject()
                    .put("jsonrpc", "2.0").put("id", req.optInt("id"))
                    .put("error", JSONObject().put("code", -32601).put("message", "Method not found"))
            }
        } catch (_: JSONException) {
            JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                .put("error", JSONObject().put("code", -32700).put("message", "Parse error"))
        }
    }

    fun serverInfo(): JSONObject = JSONObject()
        .put("uptime", System.currentTimeMillis() - startedAt)
        .put("port", port)
        .put("bridgeCount", bridgeRegistry.getAllBridges().size)
        .put("tunnel", tunnel.status().publicUrl)
}
