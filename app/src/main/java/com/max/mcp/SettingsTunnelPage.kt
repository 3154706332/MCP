package com.max.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.max.mcp.core.SettingsStore
import com.max.mcp.core.CloudflareTunnelManager

@Composable
fun SettingsTunnelPage(t: UiText, settings: SettingsStore) {
    PageScroll {
        Text(t.tunnel, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))

        GlassGroup {
            Column(Modifier.padding(16.dp)) {
                NumberSettingRow(
                    title = t.serverPort,
                    value = settings.tunnelTargetPort,
                    onValueChange = { settings.tunnelTargetPort = it }
                )
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    title = t.tunnelMode,
                    options = listOf("Quick", "Named"),
                    selectedIndex = if (settings.tunnelToken.isNotBlank()) 1 else 0,
                    onSelect = { i ->
                        if (i == 0) settings.tunnelToken = ""
                    }
                )
                if (settings.tunnelToken.isNotBlank()) {
                    OutlinedTextField(
                        value = settings.tunnelToken,
                        onValueChange = { settings.tunnelToken = it },
                        label = { Text(t.token) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(t.autoStart)
                    Switch(
                        checked = settings.tunnelAutoStart,
                        onCheckedChange = { settings.tunnelAutoStart = it }
                    )
                }
            }
        }
    }
}
