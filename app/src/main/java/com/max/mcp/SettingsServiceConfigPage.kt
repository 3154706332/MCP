package com.max.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.max.mcp.core.SettingsStore

@Composable
fun SettingsServiceConfigPage(t: UiText, settings: SettingsStore) {
    PageScroll {
        Text(t.service, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))

        GlassGroup {
            Column(Modifier.padding(16.dp)) {
                NumberSettingRow(
                    title = t.serverPort,
                    value = settings.mcpPort,
                    onValueChange = { settings.mcpPort = it }
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(t.autoStart)
                    Switch(
                        checked = settings.bootAutoStart,
                        onCheckedChange = { settings.bootAutoStart = it }
                    )
                }
            }
        }
    }
}
