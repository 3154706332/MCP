package com.max.mcp.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object PublicReachability {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class Result(val reachable: Boolean, val publicIp: String = "", val error: String = "")

    fun check(): Result = try {
        val req = Request.Builder().url("https://api.ipify.org?format=json").get().build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: "{}"
        val ip = org.json.JSONObject(body).optString("ip", "")
        Result(reachable = resp.isSuccessful, publicIp = ip)
    } catch (e: Exception) {
        Result(reachable = false, error = e.message ?: "Unknown")
    }
}