package com.max.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.max.mcp.core.McpBridgeRegistry
import com.max.mcp.core.SettingsStore
import kotlinx.coroutines.launch

@Composable
internal fun SettingsBridgeRegistryPage(t: UiText, settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry = remember { McpBridgeRegistry(context.applicationContext, settings) }
    val bridges = remember { mutableStateOf(registry.getAllBridges()) }
    var showAdd by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    PageScroll {
        Text(t.bridges, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))

        bridges.value.forEach { bridge ->
            val state = bridge.getState()
            GlassGroup {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(bridge.config.name, fontWeight = FontWeight.SemiBold)
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (state.online) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error
                            ) {
                                Text(
                                    if (state.online) "Online" else "Offline",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(bridge.config.url, style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.tools.isNotEmpty()) {
                            Text("${state.tools.size} tools", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        SecondaryActionButton(text = "Add Bridge", onClick = { showAdd = true })
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add Bridge") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newUrl, onValueChange = { newUrl = it },
                        label = { Text("URL") }, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newUrl.isNotBlank()) {
                        val id = newUrl.hashCode().toString()
                        registry.addBridge(McpBridgeRegistry.BridgeConfig(
                            id = id, name = newName.ifBlank { "Bridge-$id" }, url = newUrl
                        ))
                        bridges.value = registry.getAllBridges()
                        showAdd = false; newUrl = ""; newName = ""
                    }
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            }
        )
    }
}
