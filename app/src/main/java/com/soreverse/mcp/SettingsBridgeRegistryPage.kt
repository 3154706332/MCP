package com.soreverse.mcp

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.McpBridgeRegistry
import com.soreverse.mcp.core.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings page for managing multiple MCP bridges.
 * Supports MT Manager APK MCP and custom user-defined MCP servers.
 */
@Composable
internal fun SettingsBridgeRegistryPage(t: UiText, settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry = remember { McpBridgeRegistry(context.applicationContext, settings) }

    // Initialize registry on first load
    val initialized = remember { mutableStateOf(false) }
    if (!initialized.value) {
        registry.loadFromSettings()
        initialized.value = true
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var newBridgeId by remember { mutableStateOf("") }
    var newBridgeName by remember { mutableStateOf("") }
    var newBridgeUrl by remember { mutableStateOf("") }
    var newBridgeToken by remember { mutableStateOf("") }
    var newBridgeNamespace by remember { mutableStateOf("ext") }
    var newBridgeEnabled by remember { mutableStateOf(true) }
    var newBridgeAutoConnect by remember { mutableStateOf(true) }
    var editingBridgeId by remember { mutableStateOf<String?>(null) }

    PageScroll {
        GlassGroup {
            Text(
                if (t.zh) "MCP 桥接注册表：连接多个外部 MCP 服务器（MT 管理器 APK MCP、自定义服务等）"
                else "MCP Bridge Registry: Connect multiple external MCP servers (MT Manager APK MCP, custom servers, etc.)",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // List existing bridges
        registry.getAllBridges().forEach { bridge ->
            val state = bridge.getState()
            val config = bridge.config

            GlassGroup {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    }
                                    Text(
                                        config.name,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    // Online/Offline indicator
                                    Box(
                                        Modifier.size(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (state.online) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                                    )
                                }
                                Text(
                                    "${config.url}  •  prefix: ${config.effectivePrefix()}  •  namespace: ${config.toolNamespace()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // Probe button
                                IconButton(onClick = {
                                    scope.launch {
                                        val st = withContext(Dispatchers.IO) { bridge.healthCheck() }
                                        // Trigger recomposition by accessing state
                                        registry.getAllBridges()
                                    }
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = if (t.zh) "探测" else "Probe", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // Edit button
                                IconButton(onClick = {
                                    editingBridgeId = config.id
                                    newBridgeId = config.id
                                    newBridgeName = config.name
                                    newBridgeUrl = config.url
                                    newBridgeToken = config.token.orEmpty()
                                    newBridgeNamespace = config.namespace
                                    newBridgeEnabled = config.enabled
                                    newBridgeAutoConnect = config.autoConnect
                                    showAddDialog = true
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = if (t.zh) "编辑" else "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // Delete button (only for non-legacy bridges)
                                if (config.id != "mt_manager_apk") {
                                    IconButton(onClick = {
                                        registry.removeBridge(config.id)
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = if (t.zh) "删除" else "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        // Tool count and latency
                        if (state.online) {
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    "${if (t.zh) "工具数" else "Tools"}: ${state.tools.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${if (t.zh) "延迟" else "Latency"}: ${state.lastLatencyMs}ms  •  ${if (t.zh) "丢包" else "Loss"}: ${if (state.probeFailures > 0) "%.1f%%".format(state.probeFailures.toFloat() / maxOf(state.probes, 1) * 100) else "0.0%"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (state.serverInfo != null) {
                                    Text(
                                        "${state.serverInfo!!.name} v${state.serverInfo!!.version}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // Show tool names
                            if (state.tools.isNotEmpty()) {
                                Text(
                                    state.tools.take(8).joinToString(", ") { it.name } + if (state.tools.size > 8) "..." else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else if (state.lastError.isNotBlank()) {
                            Text(
                                "${if (t.zh) "错误" else "Error"}: ${state.lastError}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        // Add new bridge button
        GlassGroup {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        newBridgeId = ""
                        newBridgeName = ""
                        newBridgeUrl = ""
                        newBridgeToken = ""
                        newBridgeNamespace = "ext"
                        newBridgeEnabled = true
                        newBridgeAutoConnect = true
                        editingBridgeId = null
                        showAddDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    Text(if (t.zh) "添加 MCP 桥接" else "Add MCP Bridge")
                }
            }
        }

        // Legacy migration notice
        if (settings.apkMcpUrl.isNotBlank() && !registry.getAllBridges().any { it.config.id == "mt_manager_apk" }) {
            GlassGroup {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        if (t.zh) "检测到旧版 APK MCP 配置，点击可迁移到新桥接系统" else "Legacy APK MCP config detected. Tap to migrate to new bridge system",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = {
                        val config = McpBridgeRegistry.BridgeConfig(
                            id = "mt_manager_apk",
                            name = "MT Manager APK MCP",
                            url = settings.apkMcpUrl,
                            token = settings.apkMcpToken,
                            namespace = "mt_apk",
                            enabled = true,
                            autoConnect = settings.apkMcpAutoProbe,
                        )
                        registry.addBridge(config)
                    }) {
                        Text(if (t.zh) "迁移" else "Migrate")
                    }
                }
            }
        }

        // Add/Edit dialog
        if (showAddDialog) {
            val isEditing = editingBridgeId != null
            AlertDialog(
                onDismissRequest = { showAddDialog = false; editingBridgeId = null },
                title = { Text(if (isEditing) (if (t.zh) "编辑桥接" else "Edit Bridge") else (if (t.zh) "添加桥接" else "Add Bridge")) },
                text = {
                    Column(
                        Modifier.padding(16.dp).width(320.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = newBridgeId,
                            onValueChange = { newBridgeId = it },
                            label = { Text(if (t.zh) "桥接 ID (唯一)" else "Bridge ID (unique)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Text),
                        )
                        OutlinedTextField(
                            value = newBridgeName,
                            onValueChange = { newBridgeName = it },
                            label = { Text(if (t.zh) "显示名称" else "Display Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newBridgeUrl,
                            onValueChange = { newBridgeUrl = it },
                            label = { Text(if (t.zh) "MCP /mcp URL" else "MCP /mcp URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newBridgeToken,
                            onValueChange = { newBridgeToken = it },
                            label = { Text(if (t.zh) "Bearer Token (可选)" else "Bearer Token (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newBridgeNamespace,
                            onValueChange = { newBridgeNamespace = it },
                            label = { Text(if (t.zh) "命名空间前缀" else "Namespace Prefix") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = newBridgeEnabled,
                                onCheckedChange = { newBridgeEnabled = it },
                                modifier = Modifier.padding(start = 0.dp)
                            )
                            Text(if (t.zh) "启用" else "Enabled", modifier = Modifier.padding(top = 4.dp))
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = newBridgeAutoConnect,
                                onCheckedChange = { newBridgeAutoConnect = it },
                                modifier = Modifier.padding(start = 0.dp)
                            )
                            Text(if (t.zh) "自动连接" else "Auto-connect", modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val config = McpBridgeRegistry.BridgeConfig(
                                id = newBridgeId.trim(),
                                name = newBridgeName.trim(),
                                url = newBridgeUrl.trim(),
                                token = newBridgeToken.trim().takeIf { it.isNotBlank() },
                                namespace = newBridgeNamespace.trim(),
                                enabled = newBridgeEnabled,
                                autoConnect = newBridgeAutoConnect,
                            )
                            if (config.id.isNotBlank() && config.name.isNotBlank() && config.url.isNotBlank()) {
                                registry.addBridge(config)
                            }
                            showAddDialog = false
                            editingBridgeId = null
                        }
                    ) {
                        Text(if (isEditing) (if (t.zh) "保存" else "Save") else (if (t.zh) "添加" else "Add"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false; editingBridgeId = null }) {
                        Text(if (t.zh) "取消" else "Cancel")
                    }
                }
            )
        }
    }
}