package com.max.mcp.core

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CloudflareTunnelManager(private val context: Context, private val settings: SettingsStore) {

    enum class State { IDLE, CONNECTING, RUNNING, FAILED }
    enum class Mode { QUICK, NAMED }

    data class Status(
        val state: State = State.IDLE,
        val publicUrl: String = "",
        val error: String = "",
    )

    private val _status = AtomicReference(Status())
    private val stopRequested = AtomicBoolean(false)
    private var process: Process? = null

    fun status(): Status = _status.get()

    fun start() {
        stopRequested.set(false)
        _status.set(Status(state = State.CONNECTING))
        Thread {
            try {
                val mode = if (settings.tunnelToken.isNotBlank()) Mode.NAMED else Mode.QUICK
                val cmd = buildCommand(mode)
                val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                process = pb.start()
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                var url = ""
                reader.lines().forEach { line ->
                    if (stopRequested.get()) { process?.destroy(); return@forEach }
                    if (line.contains("https://") && line.contains(".trycloudflare.com")) {
                        url = "https://" + line.substringAfter("https://").substringBefore(" ")
                    }
                }
                val exitCode = process?.waitFor() ?: -1
                if (!stopRequested.get()) {
                    _status.set(Status(state = State.FAILED, error = "exit=$exitCode"))
                }
            } catch (e: Exception) {
                _status.set(Status(state = State.FAILED, error = e.message ?: "Unknown"))
            }
        }.apply { name = "cloudflared-tunnel"; start() }
        _status.set(Status(state = State.RUNNING, publicUrl = "starting..."))
    }

    fun stop() {
        stopRequested.set(true)
        process?.destroy()
        process = null
        _status.set(Status(state = State.IDLE))
    }

    private fun buildCommand(mode: Mode): List<String> {
        val cmd = mutableListOf("cloudflared", "tunnel", "--url", "http://localhost:${settings.tunnelTargetPort}")
        if (mode == Mode.NAMED) cmd.addAll(listOf("--name", "mcp-lite"))
        cmd.add("--no-autoupdate")
        return cmd
    }
}
