package com.max.mcp.mcp

import org.json.JSONObject

class ToolHandler {
    private val tools = mutableMapOf<String, (JSONObject) -> JSONObject>()
    fun register(name: String, handler: (JSONObject) -> JSONObject) { tools[name] = handler }
    fun handle(name: String, args: JSONObject): JSONObject? = tools[name]?.invoke(args)
    fun list(): List<String> = tools.keys.toList()
}
