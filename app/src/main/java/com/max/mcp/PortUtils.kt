package com.max.mcp

import java.net.ServerSocket

object PortUtils {
    fun findAvailable(from: Int = 8000, to: Int = 9000): Int {
        for (port in from..to) {
            try { ServerSocket(port).use { return port } } catch (_: Exception) {}
        }
        return from
    }
}
