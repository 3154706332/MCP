# MCP Gateway Android 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个 Android 应用 MCP Gateway，作为本机多个 MCP server 的统一聚合网关，并通过 Cloudflare Named Tunnel 把网关暴露到公网域名，让远端客户端通过单一域名调用手机上所有 MCP 工具。

**Architecture:** Fork SOMCP 后独立演进。网关自身是一个 Ktor CIO MCP server（默认 `127.0.0.1:9000`），向用户配置的上游 MCP server URL 列表发起 JSON-RPC，把各上游的 `tools/list` 合并成统一列表（带 `upstreamId__` 前缀防冲突），`tools/call` 时根据前缀路由到对应上游并转发。cloudflared 子进程把网关端口反代到 Cloudflare 边缘，远端连 `https://<域名>/mcp` 即可访问全部工具。

**Tech Stack:** Kotlin、Ktor CIO、OkHttp、Jetpack Compose + Material 3、Android Foreground Service、libcloudflared.so（jniLibs 预编译）、SharedPreferences、JUnit + MockWebServer。

**Fork 基线:** 复用 SOMCP 的 `AppLog`、`JsonUtil`、`OkHttpAwait`、`HttpHeaderSafety`、`CloudflareTunnelManager`（裁剪为 Named-only）、`McpForegroundService` 生命周期骨架、Compose 主题组件。

---

## 目录结构与文件职责

```
app/src/main/java/com/mcpgateway/
├── McpGatewayApplication.kt              # Application 入口，初始化崩溃捕获
├── MainActivity.kt                       # 单 Activity + Compose 导航
├── core/
│   ├── AppLog.kt                         # [fork] 轻量日志
│   ├── JsonUtil.kt                       # [fork] JSONObject 扩展 ok/err/obj/str/bool
│   ├── OkHttpAwait.kt                    # [fork] OkHttp 同步执行包装
│   ├── HttpHeaderSafety.kt               # [fork] header 注入防护
│   └── SettingsStore.kt                  # 上游列表/隧道/网关端口/auth 持久化
├── gateway/
│   ├── McpUpstreamClient.kt              # 单上游 JSON-RPC 客户端：initialize/tools/list/tools/call
│   ├── UpstreamRegistry.kt               # 上游列表 CRUD + 持久化 + 在线状态
│   ├── ToolMerger.kt                     # 合并去重 + 前缀编码 + 路由解析
│   ├── UpstreamHealthMonitor.kt          # 周期探测上游，触发 tools/list 刷新
│   └── McpGatewayServer.kt               # Ktor CIO MCP server，统一入口
├── tunnel/
│   └── CloudflareTunnelManager.kt        # [fork 裁剪] Named-only cloudflared 管理
├── service/
│   ├── GatewayForegroundService.kt       # 前台服务，承载网关 + 隧道
│   └── BootReceiver.kt                   # 开机自启（可选开关）
├── ui/
│   ├── ThemeTokens.kt                    # [fork] Material 3 配色
│   ├── UiComponents.kt                   # [fork] GlassGroup/NavRow/ToggleRow 等
│   ├── DashboardPage.kt                  # 总览：网关/隧道/上游状态
│   ├── UpstreamListPage.kt               # 上游 URL 增删改查 + 在线探测
│   ├── TunnelPage.kt                     # 隧道 token/启停/状态/带 token URL
│   ├── ToolBrowserPage.kt                # 浏览合并后的工具列表
│   └── SettingsHub.kt                    # 设置入口聚合
└── McpRuntimeAccess.kt                   # UI ↔ Service 桥

app/src/test/java/com/mcpgateway/
├── gateway/
│   ├── ToolMergerTest.kt
│   ├── UpstreamRegistryTest.kt
│   ├── McpUpstreamClientTest.kt          # 用 MockWebServer
│   └── McpGatewayServerTest.kt           # 用 MockWebServer 模拟上游 + Ktor TestApplication
└── tunnel/
    └── CloudflareTunnelManagerNamedTest.kt
```

**设计要点：**
- **前缀编码**：合并后工具名 = `<upstreamId>__<原始名>`，`__` 分隔。路由时按 `__` 第一次出现切分，避免原始名含 `__` 的歧义（用 indexOf 而非 split）。
- **端口隔离**：网关默认 `9000`，避开 SOMCP 的 `8000`，允许两者同机共存。
- **转发透传**：`tools/call` 的 `arguments` 原样转发给上游，不重写；错误码原样回传。
- **鉴权**：网关支持 Bearer token；隧道 URL 拼接 `?token=` 供远端 LLM 直接用。
- **隧道裁剪**：删除 SOMCP 的 QUICK 模式分支、`registerQuickTunnel`、trycloudflare API 调用，只保留 Named token 路径，降低复杂度。

---

## Task 1: Fork 基线与工程骨架

**Files:**
- Modify: `settings.gradle.kts`（改 rootProject.name）
- Modify: `app/build.gradle.kts`（改 namespace/applicationId）
- Create: `app/src/main/java/com/mcpgateway/McpGatewayApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`（改 package 引用）

- [ ] **Step 1: 复制工程并改名**

复制 SOMCP 仓库到新目录 `mcp-gateway-android/`，修改 `settings.gradle.kts`：

```kotlin
rootProject.name = "mcp-gateway-android"
```

修改 `app/build.gradle.kts` 的 namespace 与 applicationId：

```kotlin
android {
    namespace = "com.mcpgateway"
    defaultConfig {
        applicationId = "com.mcpgateway"
        // 其余保留
    }
}
```

- [ ] **Step 2: 删除 SOMCP 专属业务文件**

删除以下目录（保留 core 基础设施、tunnel、service 骨架、ui 组件）：
- `app/src/main/java/com/soreverse/mcp/engine/`（Rizin/LIEF/Blutter 等 SO 逆向引擎，网关不需要）
- `app/src/main/java/com/soreverse/mcp/mcp/`（SOMCP 自己的 38 个工具，网关不需要）
- `app/src/main/java/com/soreverse/mcp/blutter/`
- `app/src/main/cpp/`、`app/libs/`、`app/src/main/assets/blutter/`、`app/src/main/assets/rizin/`
- `app/src/main/aidl/`

把剩余 `.kt` 文件的 `package com.soreverse.mcp` 全局替换为 `com.mcpgateway`，并移动到 `com/mcpgateway/` 目录下。

- [ ] **Step 3: 写 Application 占位**

`app/src/main/java/com/mcpgateway/McpGatewayApplication.kt`：

```kotlin
package com.mcpgateway

import android.app.Application
import com.mcpgateway.core.AppLog

class McpGatewayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.i("McpGatewayApplication created")
    }
}
```

- [ ] **Step 4: 改 AndroidManifest**

把 `android:name=".SoReverseApplication"` 改为 `android:name=".McpGatewayApplication"`，删除 `<service android:name=".blutter.BlutterRunnerService" .../>`，保留 `McpForegroundService`（后续 Task 15 重命名为 `GatewayForegroundService`）。

- [ ] **Step 5: 确认能编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（可能因为引用了已删文件报错，逐个注释/删除引用直到编译通过）

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: fork SOMCP baseline, rename to mcp-gateway-android, strip SO reverse engine"
```

---

## Task 2: SettingsStore 持久化上游与隧道

**Files:**
- Create: `app/src/main/java/com/mcpgateway/core/SettingsStore.kt`
- Test: `app/src/test/java/com/mcpgateway/core/SettingsStoreTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/mcpgateway/core/SettingsStoreTest.kt`：

```kotlin
package com.mcpgateway.core

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private fun newStore(): SettingsStore = SettingsStore(RuntimeEnvironment.getApplication())

    @Test
    fun upstreams_persist_add_remove() {
        val s = newStore()
        s.setUpstreams(listOf(
            UpstreamConfig(id = "u1", name = "SOMCP", url = "http://127.0.0.1:8000/mcp", enabled = true)
        ))
        val loaded = newStore().upstreams()
        assertEquals(1, loaded.size)
        assertEquals("u1", loaded[0].id)
        assertEquals("http://127.0.0.1:8000/mcp", loaded[0].url)
        assertTrue(loaded[0].enabled)
    }

    @Test
    fun tunnel_token_masked_in_snapshot() {
        val s = newStore()
        s.tunnelToken = "secret-abcdef123456"
        val snap = s.snapshot(maskSecrets = true)
        assertEquals("****", snap.getJSONObject("tunnel").optString("tunnelToken"))
    }

    @Test
    fun gateway_port_default_9000() {
        val s = newStore()
        assertEquals(9000, s.gatewayPort)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.core.SettingsStoreTest"`
Expected: FAIL（SettingsStore 未定义）

- [ ] **Step 3: 实现 SettingsStore**

`app/src/main/java/com/mcpgateway/core/SettingsStore.kt`：

```kotlin
package com.mcpgateway.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class UpstreamConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("mcp_gateway", Context.MODE_PRIVATE)

    var gatewayPort: Int
        get() = prefs.getInt("gatewayPort", 9000)
        set(value) = prefs.edit().putInt("gatewayPort", value.coerceIn(1, 65535)).apply()

    var gatewayHost: String
        get() = prefs.getString("gatewayHost", "127.0.0.1") ?: "127.0.0.1"
        set(value) = prefs.edit().putString("gatewayHost", value).apply()

    var authEnabled: Boolean
        get() = prefs.getBoolean("authEnabled", false)
        set(value) = prefs.edit().putBoolean("authEnabled", value).apply()

    var accessToken: String
        get() = prefs.getString("accessToken", "") ?: ""
        set(value) = prefs.edit().putString("accessToken", value).apply()

    fun resetAccessToken(): String {
        val tok = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        accessToken = tok
        authEnabled = true
        return tok
    }

    // ---- Upstreams ----
    fun upstreams(): List<UpstreamConfig> {
        val raw = prefs.getString("upstreams", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<UpstreamConfig>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(UpstreamConfig(
                id = o.getString("id"),
                name = o.optString("name"),
                url = o.getString("url"),
                enabled = o.optBoolean("enabled", true),
            ))
        }
        return out
    }

    fun setUpstreams(list: List<UpstreamConfig>) {
        val arr = JSONArray()
        list.forEach { u ->
            arr.put(JSONObject()
                .put("id", u.id)
                .put("name", u.name)
                .put("url", u.url)
                .put("enabled", u.enabled))
        }
        prefs.edit().putString("upstreams", arr.toString()).apply()
    }

    // ---- Tunnel (Named only) ----
    var tunnelToken: String
        get() = prefs.getString("tunnelToken", "") ?: ""
        set(value) = prefs.edit().putString("tunnelToken", value.trim()).apply()

    var tunnelAutoStart: Boolean
        get() = prefs.getBoolean("tunnelAutoStart", false)
        set(value) = prefs.edit().putBoolean("tunnelAutoStart", value).apply()

    var tunnelReconnect: Boolean
        get() = prefs.getBoolean("tunnelReconnect", true)
        set(value) = prefs.edit().putBoolean("tunnelReconnect", value).apply()

    var tunnelKeepAlive: Boolean
        get() = prefs.getBoolean("tunnelKeepAlive", true)
        set(value) = prefs.edit().putBoolean("tunnelKeepAlive", value).apply()

    var tunnelKeepaliveIntervalSec: Int
        get() = prefs.getInt("tunnelKeepaliveIntervalSec", 15)
        set(value) = prefs.edit().putInt("tunnelKeepaliveIntervalSec", value.coerceIn(5, 300)).apply()

    var tunnelReconnectBackoffSec: Int
        get() = prefs.getInt("tunnelReconnectBackoffSec", 5)
        set(value) = prefs.edit().putInt("tunnelReconnectBackoffSec", value.coerceIn(1, 60)).apply()

    var tunnelEdgeIpVersion: String
        get() = prefs.getString("tunnelEdgeIpVersion", "4") ?: "4"
        set(value) = prefs.edit().putString("tunnelEdgeIpVersion", if (value in setOf("4", "6", "auto")) value else "4").apply()

    var tunnelProtocol: String
        get() = prefs.getString("tunnelProtocol", "http2") ?: "http2"
        set(value) = prefs.edit().putString("tunnelProtocol", if (value in setOf("http2", "quic", "auto")) value else "http2").apply()

    var tunnelHistoryUrls: String
        get() = prefs.getString("tunnelHistoryUrls", "") ?: ""
        set(value) = prefs.edit().putString("tunnelHistoryUrls", value).apply()

    var tunnelHistoryEnabled: Boolean
        get() = prefs.getBoolean("tunnelHistoryEnabled", true)
        set(value) = prefs.edit().putBoolean("tunnelHistoryEnabled", value).apply()

    private fun mask(s: String): String = if (s.length <= 8) "****" else s.take(4) + "…(" + s.length + ")…" + s.takeLast(4)

    fun snapshot(maskSecrets: Boolean = true): JSONObject = JSONObject()
        .put("gateway", JSONObject()
            .put("gatewayPort", gatewayPort)
            .put("gatewayHost", gatewayHost)
            .put("authEnabled", authEnabled)
            .put("accessToken", if (maskSecrets) mask(accessToken) else accessToken))
        .put("tunnel", JSONObject()
            .put("tunnelToken", if (maskSecrets) mask(tunnelToken) else tunnelToken)
            .put("tunnelAutoStart", tunnelAutoStart)
            .put("tunnelReconnect", tunnelReconnect)
            .put("tunnelKeepAlive", tunnelKeepAlive)
            .put("tunnelKeepaliveIntervalSec", tunnelKeepaliveIntervalSec)
            .put("tunnelReconnectBackoffSec", tunnelReconnectBackoffSec)
            .put("tunnelEdgeIpVersion", tunnelEdgeIpVersion)
            .put("tunnelProtocol", tunnelProtocol))
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.core.SettingsStoreTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mcpgateway/core/SettingsStore.kt app/src/test/java/com/mcpgateway/core/SettingsStoreTest.kt
git commit -m "feat(settings): persist upstream list, gateway port, tunnel config"
```

---

## Task 3: McpUpstreamClient — initialize 与 tools/list

**Files:**
- Create: `app/src/main/java/com/mcpgateway/gateway/McpUpstreamClient.kt`
- Test: `app/src/test/java/com/mcpgateway/gateway/McpUpstreamClientTest.kt`

- [ ] **Step 1: 写失败测试（MockWebServer 模拟上游）**

`app/src/test/java/com/mcpgateway/gateway/McpUpstreamClientTest.kt`：

```kotlin
package com.mcpgateway.gateway

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class McpUpstreamClientTest {
    private lateinit var server: MockWebServer

    @Before fun setup() {
        server = MockWebServer()
        server.start()
    }
    @After fun teardown() { server.shutdown() }

    @Test
    fun initialize_returns_protocolVersion_and_serverInfo() {
        server.enqueue(MockResponse().setBody(JSONObject()
            .put("jsonrpc", "2.0").put("id", 1)
            .put("result", JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("serverInfo", JSONObject().put("name", "upstream")))
            .toString()).setHeader("Content-Type", "application/json"))
        val client = McpUpstreamClient(id = "u1", url = server.url("/mcp").toString())
        val resp = client.initialize()
        assertEquals("2025-06-18", resp.result.optString("protocolVersion"))
    }

    @Test
    fun listTools_returns_array_of_tool_objects() {
        server.enqueue(MockResponse().setBody(JSONObject()
            .put("jsonrpc", "2.0").put("id", 2)
            .put("result", JSONObject().put("tools", JSONArray()
                .put(JSONObject().put("name", "so_open").put("description", "open so"))
                .put(JSONObject().put("name", "analyze_elf").put("description", "analyze"))))
            .toString()).setHeader("Content-Type", "application/json"))
        val client = McpUpstreamClient(id = "u1", url = server.url("/mcp").toString())
        val tools = client.listTools()
        assertEquals(2, tools.size)
        assertEquals("so_open", tools[0].name)
    }

    @Test
    fun listTools_propagates_http_error_as_exception() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val client = McpUpstreamClient(id = "u1", url = server.url("/mcp").toString())
        try {
            client.listTools()
            fail("expected exception")
        } catch (e: McpUpstreamException) {
            assertTrue(e.message!!.contains("500"))
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.gateway.McpUpstreamClientTest"`
Expected: FAIL（McpUpstreamClient 未定义）

- [ ] **Step 3: 实现 McpUpstreamClient**

`app/src/main/java/com/mcpgateway/gateway/McpUpstreamClient.kt`：

```kotlin
package com.mcpgateway.gateway

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpUpstreamException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class UpstreamTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val outputSchema: JSONObject? = null,
)

class McpUpstreamClient(
    val id: String,
    val url: String,
    private val token: String? = null,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val idSeq = AtomicInteger(0)

    private fun rpc(method: String, params: JSONObject): JSONObject {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idSeq.incrementAndGet())
            .put("method", method)
            .put("params", params)
            .toString()
        val builder = Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw McpUpstreamException("upstream $id HTTP ${resp.code}: ${text.take(200)}")
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw McpUpstreamException("upstream $id returned non-JSON: ${text.take(200)}", e)
            }
            if (json.has("error")) {
                val err = json.getJSONObject("error")
                throw McpUpstreamException("upstream $id rpc error ${err.optInt("code")}: ${err.optString("message")}")
            }
            return json
        }
    }

    fun initialize(): JSONObject = rpc("initialize", JSONObject()
        .put("protocolVersion", "2025-06-18")
        .put("capabilities", JSONObject())
        .put("clientInfo", JSONObject().put("name", "mcp-gateway").put("version", "1.0")))

    fun listTools(): List<UpstreamTool> {
        val resp = rpc("tools/list", JSONObject())
        val arr = resp.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        val out = mutableListOf<UpstreamTool>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            out.add(UpstreamTool(
                name = t.optString("name"),
                description = t.optString("description"),
                inputSchema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                outputSchema = t.optJSONObject("outputSchema"),
            ))
        }
        return out
    }

    fun callTool(name: String, args: JSONObject): JSONObject =
        rpc("tools/call", JSONObject().put("name", name).put("arguments", args))
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.gateway.McpUpstreamClientTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mcpgateway/gateway/McpUpstreamClient.kt app/src/test/java/com/mcpgateway/gateway/McpUpstreamClientTest.kt
git commit -m "feat(gateway): McpUpstreamClient with initialize/tools/list via JSON-RPC"
```

---

## Task 4: UpstreamRegistry — 上游 CRUD 与状态

**Files:**
- Create: `app/src/main/java/com/mcpgateway/gateway/UpstreamRegistry.kt`
- Test: `app/src/test/java/com/mcpgateway/gateway/UpstreamRegistryTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/mcpgateway/gateway/UpstreamRegistryTest.kt`：

```kotlin
package com.mcpgateway.gateway

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.mcpgateway.core.SettingsStore
import com.mcpgateway.core.UpstreamConfig

@RunWith(RobolectricTestRunner::class)
class UpstreamRegistryTest {

    private fun newRegistry(): UpstreamRegistry {
        val ctx = RuntimeEnvironment.getApplication()
        return UpstreamRegistry(SettingsStore(ctx))
    }

    @Test
    fun add_assigns_unique_id_and_persists() {
        val r = newRegistry()
        val a = r.add(name = "SOMCP", url = "http://127.0.0.1:8000/mcp")
        val b = r.add(name = "MT", url = "http://127.0.0.1:9001/mcp")
        assertNotEquals(a.id, b.id)
        val reloaded = newRegistry().all()
        assertEquals(2, reloaded.size)
        assertEquals("SOMCP", reloaded.first { it.id == a.id }.name)
    }

    @Test
    fun remove_by_id_persists() {
        val r = newRegistry()
        val a = r.add("x", "http://127.0.0.1:8000/mcp")
        r.remove(a.id)
        assertTrue(newRegistry().all().isEmpty())
    }

    @Test
    fun update_changes_fields() {
        val r = newRegistry()
        val a = r.add("x", "http://127.0.0.1:8000/mcp")
        r.update(a.id, name = "renamed", enabled = false)
        val reloaded = newRegistry().all().first { it.id == a.id }
        assertEquals("renamed", reloaded.name)
        assertFalse(reloaded.enabled)
    }

    @Test
    fun online_status_is_independent_of_persistence() {
        val r = newRegistry()
        val a = r.add("x", "http://127.0.0.1:8000/mcp")
        r.setOnline(a.id, true)
        assertTrue(r.snapshot().first { it.id == a.id }.online)
        // online 不持久化：新实例默认 false
        assertFalse(newRegistry().snapshot().first { it.id == a.id }.online)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.gateway.UpstreamRegistryTest"`
Expected: FAIL

- [ ] **Step 3: 实现 UpstreamRegistry**

`app/src/main/java/com/mcpgateway/gateway/UpstreamRegistry.kt`：

```kotlin
package com.mcpgateway.gateway

import com.mcpgateway.core.SettingsStore
import com.mcpgateway.core.UpstreamConfig
import java.util.concurrent.ConcurrentHashMap

data class UpstreamSnapshot(
    val config: UpstreamConfig,
    val online: Boolean,
    val lastError: String?,
    val toolCount: Int,
)

class UpstreamRegistry(private val settings: SettingsStore) {
    private val onlineMap = ConcurrentHashMap<String, Boolean>()
    private val errorMap = ConcurrentHashMap<String, String>()
    private val toolCountMap = ConcurrentHashMap<String, Int>()

    fun all(): List<UpstreamConfig> = settings.upstreams()

    fun snapshot(): List<UpstreamSnapshot> = settings.upstreams().map { cfg ->
        UpstreamSnapshot(
            config = cfg,
            online = onlineMap[cfg.id] ?: false,
            lastError = errorMap[cfg.id],
            toolCount = toolCountMap[cfg.id] ?: 0,
        )
    }

    fun add(name: String, url: String, enabled: Boolean = true): UpstreamConfig {
        val list = settings.upstreams().toMutableList()
        val cfg = UpstreamConfig(
            id = "u" + System.currentTimeMillis().toString(36) + (0 until 1000).random().toString(36),
            name = name.trim().ifBlank { url },
            url = url.trim(),
            enabled = enabled,
        )
        list.add(cfg)
        settings.setUpstreams(list)
        return cfg
    }

    fun remove(id: String) {
        val list = settings.upstreams().filterNot { it.id == id }
        settings.setUpstreams(list)
        onlineMap.remove(id)
        errorMap.remove(id)
        toolCountMap.remove(id)
    }

    fun update(id: String, name: String? = null, url: String? = null, enabled: Boolean? = null) {
        val list = settings.upstreams().map {
            if (it.id != id) it else it.copy(
                name = name?.trim()?.ifBlank { it.name } ?: it.name,
                url = url?.trim()?.ifBlank { it.url } ?: it.url,
                enabled = enabled ?: it.enabled,
            )
        }
        settings.setUpstreams(list)
    }

    fun setOnline(id: String, online: Boolean, error: String? = null, toolCount: Int? = null) {
        onlineMap[id] = online
        if (error != null) errorMap[id] = error else errorMap.remove(id)
        if (toolCount != null) toolCountMap[id] = toolCount
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.gateway.UpstreamRegistryTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mcpgateway/gateway/UpstreamRegistry.kt app/src/test/java/com/mcpgateway/gateway/UpstreamRegistryTest.kt
git commit -m "feat(gateway): UpstreamRegistry CRUD with volatile online state"
```

---

## Task 5: ToolMerger — 前缀合并与路由解析

**Files:**
- Create: `app/src/main/java/com/mcpgateway/gateway/ToolMerger.kt`
- Test: `app/src/test/java/com/mcpgateway/gateway/ToolMergerTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/mcpgateway/gateway/ToolMergerTest.kt`：

```kotlin
package com.mcpgateway.gateway

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolMergerTest {
    private val sep = "__"

    @Test
    fun merge_prefixes_tool_names_with_upstream_id() {
        val m = ToolMerger(sep)
        val merged = m.merge(mapOf(
            "u1" to listOf(stubTool("so_open"), stubTool("analyze_elf")),
            "u2" to listOf(stubTool("mt_apk_open")),
        ))
        val names = merged.map { it.name }
        assertTrue(names.contains("u1__so_open"))
        assertTrue(names.contains("u1__analyze_elf"))
        assertTrue(names.contains("u2__mt_apk_open"))
        assertEquals(3, merged.size)
    }

    @Test
    fun merge_same_name_across_upstreams_kept_distinct_by_prefix() {
        val m = ToolMerger(sep)
        val merged = m.merge(mapOf(
            "u1" to listOf(stubTool("ping")),
            "u2" to listOf(stubTool("ping")),
        ))
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.name == "u1__ping" })
        assertTrue(merged.any { it.name == "u2__ping" })
    }

    @Test
    fun route_splits_on_first_separator_only() {
        val m = ToolMerger(sep)
        // 原始工具名含 __ 也要能正确路由
        val route = m.route("u1__weird__name")
        assertEquals("u1" to "weird__name", route)
    }

    @Test
    fun route_returns_null_when_no_separator() {
        val m = ToolMerger(sep)
        assertNull(m.route("no_prefix"))
    }

    @Test
    fun route_returns_null_for_unknown_upstream() {
        val m = ToolMerger(sep, knownUpstreams = setOf("u1"))
        assertNull(m.route("uX__foo"))
    }

    private fun stubTool(name: String): UpstreamTool =
        UpstreamTool(name = name, description = "d", inputSchema = JSONObject())
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.gateway.ToolMergerTest"`
Expected: FAIL

- [ ] **Step 3: 实现 ToolMerger**

`app/src/main/java/com/mcpgateway/gateway/ToolMerger.kt`：

```kotlin
package com.mcpgateway.gateway

import org.json.JSONObject

data class MergedTool(
    val name: String,           // 带前缀：upstreamId + sep + 原始名
    val upstreamId: String,
    val originalName: String,
    val description: String,
    val inputSchema: JSONObject,
    val outputSchema: JSONObject?,
)

class ToolMerger(
    private val sep: String = "__",
    private val knownUpstreams: Set<String> = emptySet(),
) {
    fun merge(byUpstream: Map<String, List<UpstreamTool>>): List<MergedTool> {
        val out = mutableListOf<MergedTool>()
        byUpstream.forEach { (upstreamId, tools) ->
            tools.forEach { t ->
                out.add(MergedTool(
                    name = "$upstreamId$sep${t.name}",
                    upstreamId = upstreamId,
                    originalName = t.name,
                    description = "[${upstreamId}] ${t.description}",
                    inputSchema = t.inputSchema,
                    outputSchema = t.outputSchema,
                ))
            }
        }
        return out
    }

    /** 返回 (upstreamId, originalName)，无法解析或上游未知返回 null。 */
    fun route(mergedName: String): Pair<String, String>? {
        val idx = mergedName.indexOf(sep)
        if (idx <= 0) return null
        val upstreamId = mergedName.substring(0, idx)
        val originalName = mergedName.substring(idx + sep.length)
        if (originalName.isEmpty()) return null
        if (knownUpstreams.isNotEmpty() && upstreamId !in knownUpstreams) return null
        return upstreamId to originalName
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.gateway.ToolMergerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mcpgateway/gateway/ToolMerger.kt app/src/test/java/com/mcpgateway/gateway/ToolMergerTest.kt
git commit -m "feat(gateway): ToolMerger with prefix encoding and first-separator routing"
```

---

## Task 6: McpGatewayServer — JSON-RPC 调度与统一 tools/list

**Files:**
- Create: `app/src/main/java/com/mcpgateway/gateway/McpGatewayServer.kt`
- Test: `app/src/test/java/com/mcpgateway/gateway/McpGatewayServerTest.kt`

- [ ] **Step 1: 写失败测试（用 MockWebServer 当上游，Ktor 起 gateway）**

`app/src/test/java/com/mcpgateway/gateway/McpGatewayServerTest.kt`：

```kotlin
package com.mcpgateway.gateway

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class McpGatewayServerTest {
    private lateinit var upstream: MockWebServer
    private lateinit var gateway: McpGatewayServer
    private val http = OkHttpClient()

    @Before fun setup() {
        upstream = MockWebServer().also { it.start() }
        val registry = UpstreamRegistry(InMemorySettingsStore())
        registry.add("U1", upstream.url("/mcp").toString())
        gateway = McpGatewayServer(port = 0, registry = registry, host = "127.0.0.1").also { it.start() }
    }
    @After fun teardown() {
        gateway.stop()
        upstream.shutdown()
    }

    private fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()
        http.newCall(Request.Builder().url("http://127.0.0.1:${gateway.boundPort()}/mcp")
            .post(body.toRequestBody("application/json".toMediaType()))).execute().use { r ->
            return JSONObject(r.body!!.string())
        }
    }

    @Test
    fun initialize_returns_gateway_serverInfo() {
        val resp = rpc("initialize")
        assertEquals("mcp-gateway", resp.getJSONObject("result").getJSONObject("serverInfo").optString("name"))
    }

    @Test
    fun tools_list_merges_upstream_tools_with_prefix() {
        upstream.enqueue(MockResponse().setBody(JSONObject()
            .put("jsonrpc", "2.0").put("id", 1).put("result", JSONObject().put("tools", JSONArray()
                .put(JSONObject().put("name", "so_open").put("description", "open"))
                .put(JSONObject().put("name", "analyze_elf").put("description", "analyze")))))
            .setHeader("Content-Type", "application/json"))
        gateway.refresh()  // 主动拉一次上游 tools/list
        val resp = rpc("tools/list")
        val names = mutableListOf<String>()
        val arr = resp.getJSONObject("result").getJSONArray("tools")
        for (i in 0 until arr.length()) names.add(arr.getJSONObject(i).getString("name"))
        assertEquals(2, names.size)
        assertTrue(names.any { it.endsWith("__so_open") })
        assertTrue(names.any { it.endsWith("__analyze_elf") })
    }

    @Test
    fun tools_call_routes_to_upstream_and_strips_prefix() {
        // 先让 gateway 拿到 tools/list（带前缀）
        upstream.enqueue(MockResponse().setBody(JSONObject()
            .put("jsonrpc", "2.0").put("id", 1).put("result", JSONObject().put("tools", JSONArray()
                .put(JSONObject().put("name", "so_open").put("description", "open")))))
            .setHeader("Content-Type", "application/json"))
        gateway.refresh()
        // 上游收到 tools/call，返回结果
        upstream.enqueue(MockResponse().setBody(JSONObject()
            .put("jsonrpc", "2.0").put("id", 2).put("result", JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ok"))))
            .toString()).setHeader("Content-Type", "application/json"))
        val resp = rpc("tools/call", JSONObject()
            .put("name", gateway.mergedTools().first().name)
            .put("arguments", JSONObject()))
        // 验证上游收到的 name 是去掉前缀的原始名
        val upstreamReq = upstream.takeRequest()
        val upstreamBody = JSONObject(upstreamReq.body.readUtf8())
        assertEquals("so_open", upstreamBody.getJSONObject("params").optString("name"))
        assertTrue(resp.getJSONObject("result").getJSONArray("content").getJSONObject(0).optString("text") == "ok")
    }

    @Test
    fun tools_call_unknown_prefix_returns_error() {
        val resp = rpc("tools/call", JSONObject().put("name", "nope__missing").put("arguments", JSONObject()))
        assertTrue(resp.has("error") || resp.getJSONObject("result").optBoolean("isError"))
    }
}
```

> 测试用 `InMemorySettingsStore`：在测试目录新建一个实现 `SettingsStore` 接口的内存版（若 Task 2 的 SettingsStore 不是接口，先抽出 `ISettingsStore` 接口，或直接用 Robolectric 真实 SharedPreferences）。简化做法：测试里直接构造 `UpstreamRegistry` 时传入一个匿名子类持有内存 list。为减少改动，本 Task 在 `UpstreamRegistry` 构造里支持直接传入初始 list 的重载。

**补充：** 为让测试不依赖 Android SharedPreferences，给 `UpstreamRegistry` 增加一个测试用构造重载。

- [ ] **Step 2: 给 UpstreamRegistry 加测试用重载**

修改 `app/src/main/java/com/mcpgateway/gateway/UpstreamRegistry.kt`，在类顶部增加可变内存存储的可选路径（仅测试用，生产仍用 SettingsStore）：

```kotlin
class UpstreamRegistry private constructor(
    private val settings: SettingsStore?,
    private val memoryList: MutableList<UpstreamConfig>?,
) {
    constructor(settings: SettingsStore) : this(settings, null)
    constructor(initial: List<UpstreamConfig> = emptyList()) : this(null, initial.toMutableList())

    private fun load(): List<UpstreamConfig> = settings?.upstreams() ?: memoryList!!
    private fun save(list: List<UpstreamConfig>) {
        if (settings != null) settings.setUpstreams(list) else { memoryList!!.clear(); memoryList!!.addAll(list) }
    }
    // all() / add() / remove() / update() 内部把 settings.upstreams() 换成 load()，setUpstreams 换成 save()
}
```

把 `all()` 改 `return load()`，`add/remove/update` 内 `settings.upstreams()` 改 `load()`、`settings.setUpstreams(list)` 改 `save(list)`。

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.gateway.McpGatewayServerTest"`
Expected: FAIL（McpGatewayServer 未定义）

- [ ] **Step 4: 实现 McpGatewayServer**

`app/src/main/java/com/mcpgateway/gateway/McpGatewayServer.kt`：

```kotlin
package com.mcpgateway.gateway

import com.mcpgateway.core.AppLog
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.json.JSONArray
import org.json.JSONObject

class McpGatewayServer(
    private val port: Int,
    private val registry: UpstreamRegistry,
    private val host: String = "127.0.0.1",
    private val authToken: String? = null,
) {
    private var engine: EmbeddedServer<*, *>? = null
    @Volatile private var actualPort: Int = 0
    @Volatile private var merged: List<MergedTool> = emptyList()
    private val clients = mutableMapOf<String, McpUpstreamClient>()
    private val merger = ToolMerger("__")

    fun boundPort(): Int = actualPort
    fun mergedTools(): List<MergedTool> = merged

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, host = host, port = port) {
            routing {
                get("/health") { call.respondText(JSONObject().put("ok", true).put("server", "mcp-gateway").toString(), ContentType.Application.Json) }
                get("/") { call.respondText(discovery().toString(), ContentType.Application.Json) }
                get("/mcp") {
                    if (!call.authorized()) { call.respondText(authError().toString(), ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get }
                    call.respondText(discovery().toString(), ContentType.Application.Json)
                }
                post("/mcp") { handlePost(call) }
                post("/rpc") { handlePost(call) }
            }
        }.start(wait = false)
        actualPort = (engine?.resolvedConnectors()?.firstOrNull()?.toString() ?: "").let {
            // CIO 启动后取真实端口
            runCatching { engine!!.environment.connectors.first().port }.getOrDefault(port)
        }
        // 如果 port=0（随机端口），需要等启动完成；用 Channel 同步
        AppLog.i("McpGatewayServer listening on $host:$actualPort/mcp")
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 400, timeoutMillis = 1_500)
        engine = null
    }

    /** 拉取所有 enabled 上游的 tools/list，合并到 merged。 */
    fun refresh() {
        val byUpstream = mutableMapOf<String, List<UpstreamTool>>()
        registry.all().filter { it.enabled }.forEach { cfg ->
            try {
                val client = clients.getOrPut(cfg.id) { McpUpstreamClient(cfg.id, cfg.url) }
                client.initialize()
                val tools = client.listTools()
                byUpstream[cfg.id] = tools
                registry.setOnline(cfg.id, online = true, toolCount = tools.size)
            } catch (e: Exception) {
                AppLog.w("upstream ${cfg.id} (${cfg.url}) refresh failed: ${e.message}")
                registry.setOnline(cfg.id, online = false, error = e.message)
                clients.remove(cfg.id)
            }
        }
        merged = ToolMerger("__", byUpstream.keys).merge(byUpstream)
        AppLog.i("gateway merged ${merged.size} tools from ${byUpstream.size} upstreams")
    }

    private suspend fun handlePost(call: ApplicationCall) {
        if (!call.authorized()) {
            call.respondText(authError().toString(), ContentType.Application.Json, HttpStatusCode.Unauthorized); return
        }
        val body = call.receiveText()
        val resp = dispatch(body)
        call.respondText(resp.toString(), ContentType.Application.Json)
    }

    private fun dispatch(body: String): JSONObject {
        val req = try { JSONObject(body.trim()) } catch (_: Exception) { return jsonRpcError(null, -32700, "Parse error") }
        val id = req.opt("id")
        val method = req.optString("method")
        if (req.optString("jsonrpc") != "2.0" || method.isBlank()) return jsonRpcError(id, -32600, "Invalid Request")
        val params = req.optJSONObject("params") ?: JSONObject()
        val result = when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "mcp-gateway").put("version", "1.0"))
            "notifications/initialized" -> JSONObject()
            "ping" -> JSONObject().put("ok", true)
            "resources/list" -> JSONObject().put("resources", JSONArray())
            "prompts/list" -> JSONObject().put("prompts", JSONArray())
            "tools/list" -> JSONObject().put("tools", JSONArray().also { arr ->
                merged.forEach { t ->
                    val o = JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("inputSchema", t.inputSchema)
                    if (t.outputSchema != null) o.put("outputSchema", t.outputSchema)
                    arr.put(o)
                }
            })
            "tools/call" -> forwardToolCall(params)
            else -> return jsonRpcError(id, -32601, "Method not found")
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }

    private fun forwardToolCall(params: JSONObject): JSONObject {
        val mergedName = params.optString("name")
        val route = merger.route(mergedName) ?: return JSONObject()
            .put("ok", false)
            .put("error", JSONObject().put("code", "TOOL_NOT_ROUTABLE").put("message", "tool name has no valid upstream prefix: $mergedName"))
            .put("isError", true)
        val (upstreamId, originalName) = route
        val cfg = registry.all().firstOrNull { it.id == upstreamId && it.enabled }
            ?: return JSONObject().put("ok", false).put("error", JSONObject().put("code", "UPSTREAM_OFFLINE").put("message", "upstream $upstreamId not available")).put("isError", true)
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val client = clients.getOrPut(upstreamId) { McpUpstreamClient(upstreamId, cfg.url) }
        return try {
            val upstreamResp = client.callTool(originalName, args)
            upstreamResp.optJSONObject("result") ?: JSONObject().put("ok", false).put("error", JSONObject().put("code", "UPSTREAM_EMPTY_RESULT").put("message", "upstream returned no result"))
        } catch (e: Exception) {
            registry.setOnline(upstreamId, online = false, error = e.message)
            clients.remove(upstreamId)
            JSONObject().put("ok", false).put("error", JSONObject().put("code", "UPSTREAM_CALL_FAILED").put("message", e.message ?: e.javaClass.simpleName)).put("isError", true)
        }
    }

    private fun discovery(): JSONObject = JSONObject()
        .put("ok", true).put("name", "mcp-gateway").put("protocol", "MCP JSON-RPC 2.0")
        .put("endpoint", "/mcp").put("mergedToolCount", merged.size)
        .put("upstreamCount", registry.all().size)

    private fun jsonRpcError(id: Any?, code: Int, message: String) = JSONObject()
        .put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun authError() = JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
        .put("error", JSONObject().put("code", -32001).put("message", "Unauthorized"))

    private fun ApplicationCall.authorized(): Boolean {
        if (authToken.isNullOrBlank()) return true
        val bearer = request.header("Authorization")?.removePrefix("Bearer")?.trim()
        val query = request.uri.substringAfter("token=", "").substringBefore('&')
        return bearer == authToken || query == authToken
    }
}

private fun okhttp3.MediaType.Companion.json() = "application/json".toMediaType()
```

> 注：`actualPort` 取真实端口的逻辑在 Ktor CIO 下需要等启动；生产里用固定端口（默认 9000）即可，测试用 port=0 时需在 `start()` 里加 CountDownLatch 等待 `engine.environment.connectors` 就绪。若测试取端口不稳定，可在 Task 6 Step 4 后追加一个补丁：用 `embeddedServer(...).start(wait = true)` 在独立线程 + Channel 同步 port。简化：测试直接用固定端口 19000 避免随机端口复杂性。

**测试简化修订：** 把 `McpGatewayServer(port = 0, ...)` 改为 `McpGatewayServer(port = 19000, ...)`，`boundPort()` 直接返回 19000，避免 CIO 随机端口同步问题。更新测试里的 URL 用 19000。

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.gateway.McpGatewayServerTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mcpgateway/gateway/McpGatewayServer.kt app/src/main/java/com/mcpgateway/gateway/UpstreamRegistry.kt app/src/test/java/com/mcpgateway/gateway/McpGatewayServerTest.kt
git commit -m "feat(gateway): McpGatewayServer with merged tools/list and prefixed tools/call routing"
```

---

## Task 7: UpstreamHealthMonitor — 周期探测与自动刷新

**Files:**
- Create: `app/src/main/java/com/mcpgateway/gateway/UpstreamHealthMonitor.kt`
- Test: `app/src/test/java/com/mcpgateway/gateway/UpstreamHealthMonitorTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/mcpgateway/gateway/UpstreamHealthMonitorTest.kt`：

```kotlin
package com.mcpgateway.gateway

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UpstreamHealthMonitorTest {

    @Test
    fun tick_calls_refresh_on_gateway() {
        val refreshCount = AtomicInteger(0)
        val gateway = object : IRefreshable {
            override fun refresh() { refreshCount.incrementAndGet() }
        }
        val monitor = UpstreamHealthMonitor(gateway, intervalMs = 50)
        monitor.start()
        Thread.sleep(180)
        monitor.stop()
        assertTrue(refreshCount.get() >= 2)
    }

    @Test
    fun stop_is_idempotent_and_stops_loop() {
        val gateway = object : IRefreshable { override fun refresh() {} }
        val monitor = UpstreamHealthMonitor(gateway, intervalMs = 50)
        monitor.start()
        monitor.stop()
        monitor.stop()  // 不抛异常
    }

    @Test
    fun refresh_exception_does_not_kill_loop() {
        val count = AtomicInteger(0)
        val gateway = object : IRefreshable {
            override fun refresh() {
                count.incrementAndGet()
                if (count.get() == 1) throw RuntimeException("boom")
            }
        }
        val monitor = UpstreamHealthMonitor(gateway, intervalMs = 30)
        monitor.start()
        Thread.sleep(120)
        monitor.stop()
        assertTrue(count.get() >= 2)  // 异常后继续
    }
}

interface IRefreshable { fun refresh() }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.gateway.UpstreamHealthMonitorTest"`
Expected: FAIL

- [ ] **Step 3: 让 McpGatewayServer 实现 IRefreshable**

在 `McpGatewayServer` 类声明加 `: IRefreshable`（`refresh()` 已存在，签名匹配）。把 `IRefreshable` 接口移到 `com.mcpgateway.gateway` 包顶层（已在测试文件定义，需移到独立文件避免重复）。

新建 `app/src/main/java/com/mcpgateway/gateway/IRefreshable.kt`：

```kotlin
package com.mcpgateway.gateway

interface IRefreshable {
    fun refresh()
}
```

测试文件里删除 `interface IRefreshable` 定义。

- [ ] **Step 4: 实现 UpstreamHealthMonitor**

`app/src/main/java/com/mcpgateway/gateway/UpstreamHealthMonitor.kt`：

```kotlin
package com.mcpgateway.gateway

import com.mcpgateway.core.AppLog
import java.util.concurrent.atomic.AtomicBoolean

class UpstreamHealthMonitor(
    private val target: IRefreshable,
    private val intervalMs: Long = 15_000,
) {
    @Volatile private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        running.set(true)
        thread = Thread({
            while (running.get() && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) { break }
                if (!running.get()) break
                try {
                    target.refresh()
                } catch (e: Throwable) {
                    AppLog.w("health monitor refresh failed: ${e.message}")
                }
            }
        }, "upstream-health").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.gateway.UpstreamHealthMonitorTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mcpgateway/gateway/IRefreshable.kt app/src/main/java/com/mcpgateway/gateway/UpstreamHealthMonitor.kt app/src/main/java/com/mcpgateway/gateway/McpGatewayServer.kt app/src/test/java/com/mcpgateway/gateway/UpstreamHealthMonitorTest.kt
git commit -m "feat(gateway): UpstreamHealthMonitor with exception-tolerant refresh loop"
```

---

## Task 8: CloudflareTunnelManager — Named-only 裁剪

**Files:**
- Create: `app/src/main/java/com/mcpgateway/tunnel/CloudflareTunnelManager.kt`（fork SOMCP 后裁剪）
- Test: `app/src/test/java/com/mcpgateway/tunnel/CloudflareTunnelManagerNamedTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/mcpgateway/tunnel/CloudflareTunnelManagerNamedTest.kt`：

```kotlin
package com.mcpgateway.tunnel

import org.junit.Assert.*
import org.junit.Test

class CloudflareTunnelManagerNamedTest {

    @Test
    fun start_with_empty_token_returns_failed_without_spawning() {
        val mgr = CloudflareTunnelManager(
            binaryProvider = { null },  // 无二进制
            token = "",
            targetPort = 9000,
            onState = {},
        )
        val status = mgr.start()
        assertEquals(CloudflareTunnelManager.State.FAILED, status.state)
        assertEquals("named tunnel token is empty", status.message)
    }

    @Test
    fun start_with_missing_binary_returns_failed() {
        val mgr = CloudflareTunnelManager(
            binaryProvider = { java.io.File("/nonexistent/libcloudflared.so") },
            token = "valid-token-xyz",
            targetPort = 9000,
            onState = {},
        )
        val status = mgr.start()
        assertEquals(CloudflareTunnelManager.State.FAILED, status.state)
        assertTrue(status.message!!.contains("libcloudflared"))
    }

    @Test
    fun parse_public_url_from_cloudflared_stdout() {
        val url = CloudflareTunnelManager.parsePublicUrl(
            "2026-08-02 INF +-----------------------------------------+",
            "2026-08-02 INF |  Your quick Tunnel has been created!    |",
            "2026-08-02 INF |  https://foo-bar.example.cfargotunnel.com  |",
        )
        assertEquals("https://foo-bar.example.cfargotunnel.com", url)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.tunnel.CloudflareTunnelManagerNamedTest"`
Expected: FAIL

- [ ] **Step 3: 实现 Named-only CloudflareTunnelManager**

`app/src/main/java/com/mcpgateway/tunnel/CloudflareTunnelManager.kt`（从 SOMCP fork，删除 QUICK 相关，构造改为依赖注入便于测试）：

```kotlin
package com.mcpgateway.tunnel

import com.mcpgateway.core.AppLog
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

class CloudflareTunnelManager(
    private val binaryProvider: () -> File?,
    private val token: String,
    private val targetPort: Int,
    private val edgeIpVersion: String = "4",
    private val protocol: String = "http2",
    private val onState: (TunnelStatus) -> Unit = {},
) {
    enum class State { STOPPED, STARTING, RUNNING, FAILED }

    data class TunnelStatus(
        val state: State = State.STOPPED,
        val publicUrl: String? = null,
        val message: String = "",
        val pid: Int = 0,
    )

    private val _status = AtomicReference(TunnelStatus())
    fun status(): TunnelStatus = _status.get()
    private var process: Process? = null
    private var watchThread: Thread? = null
    private var healthThread: Thread? = null
    @Volatile private var stopRequested = false
    private val generation = AtomicInteger(0)

    @Synchronized
    private fun transition(state: State, publicUrl: String? = null, message: String? = null, pid: Int = 0): TunnelStatus {
        val next = TunnelStatus(state, publicUrl ?: _status.get().publicUrl, message ?: _status.get().message, if (pid != 0) pid else _status.get().pid)
        _status.set(next)
        onState(next)
        return next
    }

    fun start(): TunnelStatus {
        stopRequested = false
        if (token.isBlank()) {
            return fail("named tunnel token is empty")
        }
        teardownForRestart()
        val bin = binaryProvider()
        if (bin == null || !bin.exists()) {
            return fail("libcloudflared.so not found in nativeLibraryDir")
        }
        val gen = generation.incrementAndGet()
        transition(State.STARTING, publicUrl = null, message = "starting")
        val cmd = mutableListOf(
            bin.absolutePath, "tunnel", "--no-autoupdate",
            "--edge-ip-version", edgeIpVersion, "run", "--token", token,
        )
        val edges = edgeIps()
        if (edges.isNotEmpty()) {
            val insertAt = cmd.indexOf("run")
            edges.forEachIndexed { i, ip -> cmd.addAll(insertAt + i * 2, listOf("--edge", ip)) }
        }
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["NO_AUTOUPDATE"] = "true"
            val p = pb.start()
            process = p
            transition(State.STARTING, message = "cloudflared spawned", pid = childPid(p))
            startWatch(gen, p)
            startHealth(gen)
            _status.get()
        } catch (e: Exception) {
            fail("spawn failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun startWatch(gen: Int, p: Process) {
        val t = Thread({
            try {
                InputStreamReader(p.inputStream, Charsets.UTF_8).use { reader ->
                    val buf = CharArray(2048)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            String(buf, 0, n).split('\n').forEach { line ->
                                if (line.isNotBlank()) {
                                    AppLog.i("clfl: $line")
                                    parsePublicUrl(line)?.let { url ->
                                        if (generation.get() == gen && _status.get().state == State.STARTING) {
                                            transition(State.RUNNING, publicUrl = url, message = "running")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                if (!stopRequested) AppLog.w("tunnel stream end: ${e.message}")
            } finally {
                val exit = runCatching { p.exitValue() }.getOrDefault(-1)
                if (!stopRequested && generation.get() == gen) {
                    transition(State.STOPPED, publicUrl = null, message = "process exited (code=$exit)")
                }
            }
        }, "cloudflared-watch")
        t.isDaemon = true
        watchThread = t
        t.start()
    }

    private fun startHealth(gen: Int) {
        val t = Thread({
            try {
                while (!stopRequested && generation.get() == gen) {
                    Thread.sleep(15_000)
                    if (stopRequested || generation.get() != gen) break
                    val p = process ?: break
                    if (!p.isAlive) { transition(State.FAILED, message = "process not alive"); break }
                }
            } catch (_: InterruptedException) {}
        }, "cloudflared-health")
        t.isDaemon = true
        healthThread = t
        t.start()
    }

    fun requestStop() { stopRequested = true; generation.incrementAndGet() }

    @Synchronized
    fun stop() {
        stopRequested = true
        healthThread?.interrupt(); healthThread = null
        watchThread?.interrupt(); watchThread = null
        process?.let { p ->
            runCatching { p.destroy() }
            val exited = runCatching { p.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!exited) { runCatching { p.destroyForcibly() }; runCatching { p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) } }
        }
        process = null
        transition(State.STOPPED, publicUrl = null, message = "stopped")
    }

    @Synchronized
    private fun teardownForRestart() {
        process?.let { p ->
            runCatching { p.destroy() }
            val exited = runCatching { p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!exited) { runCatching { p.destroyForcibly() }; runCatching { p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) } }
        }
        process = null
    }

    private fun fail(msg: String): TunnelStatus {
        AppLog.w("tunnel: $msg")
        return transition(State.FAILED, publicUrl = null, message = msg)
    }

    private fun childPid(p: Process): Int = try {
        val m = Process::class.java.getMethod("pid")
        (m.invoke(p) as Long).toInt()
    } catch (_: Throwable) { 0 }

    private fun edgeIps(): List<String> {
        val hosts = if (edgeIpVersion == "6") listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")
                    else listOf("region1.argotunnel.com", "region2.argotunnel.com")
        return hosts.flatMap { h ->
            try {
                InetAddress.getAllByName(h).filter { edgeIpVersion != "4" || it is Inet4Address }.map { "${it.hostAddress}:7844" }
            } catch (e: Exception) { emptyList() }
        }
    }

    companion object {
        private val URL_PATTERN = Pattern.compile("https://[a-z0-9-]+\\.(trycloudflare\\.com|cfargotunnel\\.com)", Pattern.CASE_INSENSITIVE)

        fun parsePublicUrl(vararg lines: String): String? {
            lines.forEach { line ->
                val m = URL_PATTERN.matcher(line)
                if (m.find()) return m.group()
            }
            return null
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mcpgateway.tunnel.CloudflareTunnelManagerNamedTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mcpgateway/tunnel/CloudflareTunnelManager.kt app/src/test/java/com/mcpgateway/tunnel/CloudflareTunnelManagerNamedTest.kt
git commit -m "feat(tunnel): Named-only CloudflareTunnelManager forked from SOMCP, DI-friendly for tests"
```

---

## Task 9: GatewayForegroundService — 生命周期宿主

**Files:**
- Create: `app/src/main/java/com/mcpgateway/service/GatewayForegroundService.kt`
- Modify: `app/src/main/AndroidManifest.xml`（替换 service 声明）

- [ ] **Step 1: 实现 GatewayForegroundService**

`app/src/main/java/com/mcpgateway/service/GatewayForegroundService.kt`（fork SOMCP `McpForegroundService`，去掉 floating bubble，简化为网关+隧道承载）：

```kotlin
package com.mcpgateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.mcpgateway.MainActivity
import com.mcpgateway.core.AppLog
import com.mcpgateway.core.SettingsStore
import com.mcpgateway.gateway.McpGatewayServer
import com.mcpgateway.gateway.UpstreamHealthMonitor
import com.mcpgateway.gateway.UpstreamRegistry
import com.mcpgateway.tunnel.CloudflareTunnelManager
import java.io.File

class GatewayForegroundService : Service() {
    private var server: McpGatewayServer? = null
    private var registry: UpstreamRegistry? = null
    private var monitor: UpstreamHealthMonitor? = null
    private var tunnel: CloudflareTunnelManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startAll()
            ACTION_STOP -> { running = false; runCatching { tunnel?.requestStop() }; stopSelf() }
        }
        return START_STICKY
    }

    private fun startAll() {
        val settings = SettingsStore(this)
        createChannel()
        startForeground(1001, notification("MCP Gateway on ${settings.gatewayHost}:${settings.gatewayPort}"))
        acquireWakeLock(settings)
        if (server != null) { running = true; return }
        val reg = UpstreamRegistry(settings)
        registry = reg
        val srv = McpGatewayServer(
            port = settings.gatewayPort,
            registry = reg,
            host = settings.gatewayHost,
            authToken = if (settings.authEnabled) settings.accessToken else null,
        ).also { it.start(); it.refresh() }
        server = srv
        currentServer = srv
        monitor = UpstreamHealthMonitor(srv, settings.tunnelKeepaliveIntervalSec * 1000L).also { it.start() }
        running = true
        maybeAutoStartTunnel(settings)
        AppLog.i("Gateway started on ${settings.gatewayHost}:${settings.gatewayPort}")
    }

    private fun maybeAutoStartTunnel(settings: SettingsStore) {
        if (!settings.tunnelAutoStart || settings.tunnelToken.isBlank()) return
        Thread {
            if (!running) return@Thread
            val mgr = CloudflareTunnelManager(
                binaryProvider = { File(applicationInfo.nativeLibraryDir, "libcloudflared.so").takeIf { it.exists() } },
                token = settings.tunnelToken,
                targetPort = settings.gatewayPort,
                edgeIpVersion = settings.tunnelEdgeIpVersion,
                protocol = settings.tunnelProtocol,
            )
            tunnel = mgr
            runCatching { mgr.start() }
        }.apply { isDaemon = true; name = "tunnel-autostart" }.start()
    }

    override fun onDestroy() {
        running = false
        runCatching { tunnel?.stop() }
        runCatching { monitor?.stop() }
        runCatching { server?.stop() }
        wakeLock?.takeIf { it.isHeld }?.release()
        currentServer = null
        AppLog.i("Gateway service destroyed")
        super.onDestroy()
    }

    private fun acquireWakeLock(settings: SettingsStore) {
        // 简化：默认不持有 wakeLock 除非配置；此处保留接口
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "MCP Gateway", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("MCP Gateway").setContentText(text)
            .setSmallIcon(com.mcpgateway.R.drawable.ic_stat_somcp).setOngoing(true).build()
    }

    companion object {
        const val ACTION_START = "com.mcpgateway.START"
        const val ACTION_STOP = "com.mcpgateway.STOP"
        private const val CHANNEL_ID = "mcp_gateway"
        @Volatile private var running: Boolean = false
        @Volatile var currentServer: McpGatewayServer? = null
            private set
        fun isRunning(): Boolean = running
        fun start(context: Context) {
            val intent = Intent(context, GatewayForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) {
            running = false
            context.startService(Intent(context, GatewayForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
```

- [ ] **Step 2: 更新 AndroidManifest**

把 `<service android:name=".service.McpForegroundService" .../>` 改为：

```xml
<service
    android:name=".service.GatewayForegroundService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

- [ ] **Step 3: 确认编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（注意 `R.drawable.ic_stat_somcp` 需存在，从 SOMCP fork 时保留该 drawable 或改名；若改名同步更新引用）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mcpgateway/service/GatewayForegroundService.kt app/src/main/AndroidManifest.xml
git commit -m "feat(service): GatewayForegroundService hosts gateway server + tunnel + health monitor"
```

---

## Task 10: UI — Dashboard 与上游列表页

**Files:**
- Create: `app/src/main/java/com/mcpgateway/ui/DashboardPage.kt`
- Create: `app/src/main/java/com/mcpgateway/ui/UpstreamListPage.kt`
- Create: `app/src/main/java/com/mcpgateway/McpRuntimeAccess.kt`

- [ ] **Step 1: 实现 McpRuntimeAccess 桥**

`app/src/main/java/com/mcpgateway/McpRuntimeAccess.kt`：

```kotlin
package com.mcpgateway

import android.content.Context
import com.mcpgateway.core.SettingsStore
import com.mcpgateway.gateway.McpGatewayServer
import com.mcpgateway.service.GatewayForegroundService

internal fun activeServer(context: Context): McpGatewayServer? = GatewayForegroundService.currentServer

internal fun activeSettings(context: Context): SettingsStore = SettingsStore(context)
```

- [ ] **Step 2: 实现 UpstreamListPage**

`app/src/main/java/com/mcpgateway/ui/UpstreamListPage.kt`（Compose，复用 SOMCP 的 GlassGroup/NavRow/ToggleRow 组件，fork 后已在 ui/ 下）：

```kotlin
package com.mcpgateway.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcpgateway.core.UpstreamConfig
import com.mcpgateway.core.SettingsStore
import com.mcpgateway.gateway.UpstreamRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UpstreamListPage(settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(settings.upstreams()) }
    var showAdd by remember { mutableStateOf(false) }

    PageScroll {
        GlassGroup(title = "上游 MCP Server") {
            if (list.isEmpty()) {
                Text("还没有上游。点右下角 + 添加一个 MCP server URL。", modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(list) { cfg ->
                    UpstreamRow(cfg, onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) { UpstreamRegistry(settings).remove(cfg.id) }
                            list = settings.upstreams()
                        }
                    }, onToggle = { en ->
                        scope.launch {
                            withContext(Dispatchers.IO) { UpstreamRegistry(settings).update(cfg.id, enabled = en) }
                            list = settings.upstreams()
                        }
                    })
                    GroupDivider()
                }
            }
        }
    }
    FloatingActionButton(onClick = { showAdd = true }, modifier = Modifier.padding(16.dp)) {
        Icon(Icons.Default.Add, contentDescription = "添加")
    }
    if (showAdd) {
        AddUpstreamDialog(onConfirm = { name, url ->
            scope.launch {
                withContext(Dispatchers.IO) { UpstreamRegistry(settings).add(name, url) }
                list = settings.upstreams()
            }
            showAdd = false
        }, onDismiss = { showAdd = false })
    }
}

@Composable
private fun UpstreamRow(cfg: UpstreamConfig, onDelete: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(cfg.name, style = MaterialTheme.typography.bodyLarge)
            Text(cfg.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = cfg.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除") }
    }
}

@Composable
private fun AddUpstreamDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://127.0.0.1:") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加上游 MCP Server") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { if (url.isNotBlank()) onConfirm(name, url) }) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
```

- [ ] **Step 3: 实现 DashboardPage**

`app/src/main/java/com/mcpgateway/ui/DashboardPage.kt`：

```kotlin
package com.mcpgateway.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcpgateway.activeServer
import com.mcpgateway.activeSettings
import com.mcpgateway.service.GatewayForegroundService
import kotlinx.coroutines.delay

@Composable
internal fun DashboardPage() {
    val context = LocalContext.current
    val settings = remember { activeSettings(context) }
    var running by remember { mutableStateOf(GatewayForegroundService.isRunning()) }
    var mergedCount by remember { mutableStateOf(0) }
    var upstreamCount by remember { mutableStateOf(settings.upstreams().size) }
    LaunchedEffect(Unit) {
        while (true) {
            running = GatewayForegroundService.isRunning()
            mergedCount = activeServer(context)?.mergedTools()?.size ?: 0
            upstreamCount = settings.upstreams().size
            delay(2000)
        }
    }
    PageScroll {
        GlassGroup(title = "网关状态") {
            StatusRow("服务", if (running) "运行中" else "已停止")
            GroupDivider()
            StatusRow("监听", "${settings.gatewayHost}:${settings.gatewayPort}")
            GroupDivider()
            StatusRow("上游数量", upstreamCount.toString())
            GroupDivider()
            StatusRow("合并工具数", mergedCount.toString())
            GroupDivider()
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) {
                    Button(onClick = { GatewayForegroundService.start(context) }) { Text("启动网关") }
                } else {
                    Button(onClick = { GatewayForegroundService.stop(context) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("停止网关") }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
```

- [ ] **Step 4: 确认编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mcpgateway/McpRuntimeAccess.kt app/src/main/java/com/mcpgateway/ui/DashboardPage.kt app/src/main/java/com/mcpgateway/ui/UpstreamListPage.kt
git commit -m "feat(ui): Dashboard status page and UpstreamListPage with add/remove/toggle"
```

---

## Task 11: UI — 隧道页（Named token + 带 token URL）

**Files:**
- Create: `app/src/main/java/com/mcpgateway/ui/TunnelPage.kt`

- [ ] **Step 1: 实现 TunnelPage**

`app/src/main/java/com/mcpgateway/ui/TunnelPage.kt`：

```kotlin
package com.mcpgateway.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcpgateway.activeServer
import com.mcpgateway.activeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TunnelPage() {
    val context = LocalContext.current
    val settings = remember { activeSettings(context) }
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(settings.tunnelToken) }
    var publicUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            publicUrl = activeServer(context)?.let { null }  // 隧道状态从 service 取，此处占位
            delay(3000)
        }
    }
    PageScroll {
        GlassGroup(title = "Cloudflare Named Tunnel") {
            Text("填入 Cloudflare Tunnel token（在 Cloudflare Zero Trust → Tunnels → 对应隧道 → Install 里复制）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it; settings.tunnelToken = it },
                label = { Text("Tunnel token") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(14.dp),
            )
            ToggleRow("随服务自动启动", settings.tunnelAutoStart) { settings.tunnelAutoStart = it }
            GroupDivider()
            ToggleRow("断线自动重连", settings.tunnelReconnect) { settings.tunnelReconnect = it }
            GroupDivider()
            ToggleRow("保活探测", settings.tunnelKeepAlive) { settings.tunnelKeepAlive = it }
        }
        GlassGroup(title = "公网访问") {
            publicUrl?.let { url ->
                NavRow(url, "点击复制公网地址", Icons.Default.Public, onClick = { copy(context, url) })
                if (settings.authEnabled && settings.accessToken.isNotBlank() && url.startsWith("https://")) {
                    GroupDivider()
                    val full = "$url/mcp?token=${settings.accessToken}"
                    NavRow("带 token 的 MCP 链接", full, Icons.Default.Link, onClick = { copy(context, full) })
                }
            } ?: Text("隧道未运行。启动后此处显示公网 URL。", modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GlassGroup {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            // 启动隧道：通过 service 持有的 tunnel 引用，或重新构造
                            // 简化：这里调用 service 单例方法（Task 9 里需暴露 startTunnel/stopTunnel）
                        }
                    }
                    Toast.makeText(context, "隧道启动中…", Toast.LENGTH_SHORT).show()
                }) { Text("启动隧道") }
                OutlinedButton(onClick = { /* stop tunnel */ }) { Text("停止隧道") }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("mcp", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}
```

> **补丁：** Task 9 的 `GatewayForegroundService` 需暴露 `startTunnel()` / `stopTunnel()` / `tunnelStatus()` 给 UI 调用。在 Task 9 的 companion 里加：

```kotlin
fun startTunnel(context: Context) {
    // 通过 currentServer 或新 intent 触发；简化为发 ACTION_START_TUNNEL intent
    context.startService(Intent(context, GatewayForegroundService::class.java).setAction("com.mcpgateway.TUNNEL_START"))
}
fun stopTunnel(context: Context) {
    context.startService(Intent(context, GatewayForegroundService::class.java).setAction("com.mcpgateway.TUNNEL_STOP"))
}
```

并在 `onStartCommand` 的 when 分支增加 `"com.mcpgateway.TUNNEL_START"` / `"com.mcpgateway.TUNNEL_STOP"` 处理，调用 `tunnel?.start()` / `tunnel?.stop()`。把 TunnelPage 的启动/停止按钮接到这两个方法。

- [ ] **Step 2: 确认编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mcpgateway/ui/TunnelPage.kt app/src/main/java/com/mcpgateway/service/GatewayForegroundService.kt
git commit -m "feat(ui): TunnelPage with Named token config and public URL with token"
```

---

## Task 12: MainActivity 导航聚合

**Files:**
- Modify: `app/src/main/java/com/mcpgateway/MainActivity.kt`

- [ ] **Step 1: 实现 MainActivity 底部导航**

`app/src/main/java/com/mcpgateway/MainActivity.kt`（替换 SOMCP 的 MainActivity，三个 tab：Dashboard / 上游 / 隧道）：

```kotlin
package com.mcpgateway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.mcpgateway.ui.DashboardPage
import com.mcpgateway.ui.TunnelPage
import com.mcpgateway.ui.UpstreamListPage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var tab by remember { mutableStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Dashboard, null) }, label = { Text("总览") })
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.List, null) }, label = { Text("上游") })
                            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Public, null) }, label = { Text("隧道") })
                        }
                    }
                ) { pad ->
                    when (tab) {
                        0 -> DashboardPage()
                        1 -> UpstreamListPage(activeSettings(this))
                        2 -> TunnelPage()
                    }
                    androidx.compose.foundation.layout.Box(Modifier.padding(pad))
                }
            }
        }
    }
}
```

- [ ] **Step 2: 确认编译并装机**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mcpgateway/MainActivity.kt
git commit -m "feat(ui): MainActivity with 3-tab navigation (Dashboard/Upstream/Tunnel)"
```

---

## Task 13: libcloudflared.so 打包与端到端冒烟

**Files:**
- Modify: `app/build.gradle.kts`（确认 jniLibs 配置）
- Create: `app/src/main/jniLibs/<abi>/libcloudflared.so`（从 SOMCP 或官方 release 复制）

- [ ] **Step 1: 放入 cloudflared 二进制**

从 SOMCP APK 或 Cloudflare 官方 `cloudflared-android` release 提取 `libcloudflared.so`，按 ABI 放入：

```
app/src/main/jniLibs/arm64-v8a/libcloudflared.so
app/src/main/jniLibs/armeabi-v7a/libcloudflared.so
app/src/main/jniLibs/x86_64/libcloudflared.so
```

- [ ] **Step 2: 确认 build.gradle 打包配置**

`app/build.gradle.kts` 的 android 块保留：

```kotlin
packaging {
    jniLibs { useLegacyPackaging = true }
}
defaultConfig {
    ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
}
```

确认 AndroidManifest 已有 `android:extractNativeLibs="true"`（SOMCP fork 时保留）。

- [ ] **Step 3: 构建并装机冒烟**

Run:
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

手动验证：
1. 打开 App，上游页添加 `http://127.0.0.1:8000/mcp`（前提手机上 SOMCP 在跑）
2. 总览页点启动网关
3. 用 `adb forward tcp:9000 tcp:9000`，电脑 curl `http://127.0.0.1:9000/mcp` POST `initialize`，应返回 `mcp-gateway` serverInfo
4. POST `tools/list`，应看到带 `uXXXX__so_open` 前缀的合并工具
5. POST `tools/call` name=`<前缀>__so_open`，应转发到 SOMCP
6. 隧道页填 Cloudflare token，启动隧道，等公网 URL 出现
7. 电脑 curl `https://<公网域名>/mcp` POST `tools/list`，应看到同样合并工具

Expected: 全部通过

- [ ] **Step 4: Commit**

```bash
git add app/src/main/jniLibs/ app/build.gradle.kts
git commit -m "feat(packaging): bundle libcloudflared.so for arm64/armv7/x86_64, end-to-end smoke verified"
```

---

## Task 14: 鉴权与安全收尾

**Files:**
- Modify: `app/src/main/java/com/mcpgateway/gateway/McpGatewayServer.kt`（已含 authorized()，确认覆盖所有路由）
- Modify: `app/src/main/java/com/mcpgateway/ui/DashboardPage.kt`（显示 auth 状态 + 一键生成 token）

- [ ] **Step 1: 确认 /mcp、/rpc、tools/call 都过 authorized()**

Review `McpGatewayServer` 的 routing 块，每个 `post` 和 `get("/mcp")` 入口都调用 `call.authorized()`，未通过返回 401。当前实现已覆盖。

- [ ] **Step 2: Dashboard 加 auth 开关与 token 展示**

在 `DashboardPage.kt` 的 GlassGroup 内追加：

```kotlin
GroupDivider()
StatusRow("鉴权", if (settings.authEnabled) "已开启" else "关闭")
if (settings.authEnabled && settings.accessToken.isNotBlank()) {
    GroupDivider()
    NavRow("Access Token", settings.accessToken, Icons.Default.Key, onClick = { copy(context, settings.accessToken) })
    GroupDivider()
    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { settings.resetAccessToken(); /* 刷新显示 */ }) { Text("重新生成 token") }
    }
}
```

- [ ] **Step 3: 确认编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mcpgateway/ui/DashboardPage.kt
git commit -m "feat(auth): surface auth status and token regen on dashboard"
```

---

## Self-Review

**1. Spec coverage check:**
- ✅ 连接 cloudflared 隧道 → Task 8（Named-only manager）+ Task 9（service 集成）+ Task 11（UI）
- ✅ 手机上 127.0.0.1 连接所有 MCP 工具 → Task 3（UpstreamClient）+ Task 4（Registry）+ Task 10（上游 URL 配置 UI）
- ✅ 连接隧道后通过域名调用手机所有 MCP 工具 → Task 5/6（合并+路由）+ Task 8（隧道）+ Task 13（端到端冒烟）
- ✅ Fork SOMCP → Task 1
- ✅ 统一 MCP 网关 → Task 5/6（ToolMerger + McpGatewayServer）
- ✅ 用户自定义 URL 列表 → Task 4/10
- ✅ Named 隧道 → Task 8（裁剪掉 QUICK）

**2. Placeholder scan:**
- Task 11 Step 1 的隧道启停按钮有 `/* stop tunnel */` 占位 → 已在补丁里说明接到 `GatewayForegroundService.stopTunnel(context)`，需在实现时落实，不留空。
- Task 11 的 `publicUrl` 状态获取写了占位注释 → 需在 Task 9 的 service 暴露 `tunnelStatus()` 供 UI 轮询。这是已知的实现细节，落码时补上。

**3. Type consistency:**
- `UpstreamConfig` 在 Task 2 定义（id/name/url/enabled），Task 4 的 `UpstreamRegistry` 与 Task 10 UI 都用同一字段 ✅
- `UpstreamTool`（Task 3）→ `MergedTool`（Task 5）→ `McpGatewayServer.merged`（Task 6）类型链一致 ✅
- `ToolMerger.route()` 返回 `Pair<String, String>?`，`forwardToolCall` 解构 `(upstreamId, originalName)` 一致 ✅
- `IRefreshable.refresh()` 签名与 `McpGatewayServer.refresh()` 一致 ✅
- `CloudflareTunnelManager.TunnelStatus` 字段在 Task 8 与 Task 11 UI 引用一致（state/publicUrl/message）✅

**已知实现期补丁点（落码时处理，不阻塞计划）：**
1. Task 9 service 需暴露 `tunnelStatus(): TunnelStatus?` 给 Task 11 UI 轮询 publicUrl。
2. Task 11 启停按钮接 `GatewayForegroundService.startTunnel/stopTunnel`，service 内 `onStartCommand` 加对应 action 分支。
3. Task 6 测试用固定端口 19000 而非随机端口，避免 CIO 同步复杂性。
4. Task 1 fork 时 `R.drawable.ic_stat_somcp` 资源需保留或重命名同步引用。
5. Robolectric 测试需在 `app/build.gradle.kts` 加 `testOptions { unitTests { isIncludeAndroidResources = true } }` 和 robolectric 依赖。

---

## 执行建议

**两种执行方式：**

1. **Subagent-Driven（推荐）** — 每个 Task 派一个新 subagent 实现，两阶段 review，快速迭代。适合本计划这种任务边界清晰、可并行的工程。
2. **Inline Execution** — 在当前会话按 Task 顺序批量执行，带 checkpoint review。

**建议执行顺序：** Task 1 → 2 → 3 → 4 → 5 → 6（核心网关链路打通）→ 7 → 8 → 9 → 13（先端到端冒烟，UI 可后补）→ 10 → 11 → 12 → 14。Task 6 是最大风险点（Ktor + JSON-RPC + 路由），优先跑通测试。

**风险点：**
- Task 8 cloudflared 子进程在 Android 上的稳定性（fork/内存/SIGCHLD）——SOMCP 已踩过的坑，fork 时务必带上 `requestStop`/同步 `onDestroy`/两阶段 destroy 这些防御代码，本计划的裁剪版已保留核心。
- Task 6 测试端口与 Ktor CIO 启动同步——用固定端口规避。
- libcloudflared.so 的 ABI 覆盖与体积——若只支持 arm64 可减小 APK，但 x86_64 模拟器测试需要。

**选哪种执行方式？**
