package com.max.mcp.core

import android.util.Log

object AppLog {
    private const val TAG = "MCPLite"
    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String) = Log.w(TAG, msg)
    fun e(msg: String) = Log.e(TAG, msg)
    fun e(msg: String, t: Throwable) = Log.e(TAG, msg, t)
}