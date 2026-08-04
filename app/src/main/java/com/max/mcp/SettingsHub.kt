package com.max.mcp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.max.mcp.core.SettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHub(t: UiText, settings: SettingsStore) {
    var dest by remember { mutableStateOf(SettingsDest.Root) }

    if (dest != SettingsDest.Root) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(when (dest) {
                        SettingsDest.Tunnel -> t.tunnel
                        SettingsDest.BridgeRegistry -> t.bridges
                        SettingsDest.ServiceConfig -> t.service
                        else -> t.settings
                    })},
                    navigationIcon = {
                        IconButton(onClick = { dest = SettingsDest.Root }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (dest) {
                    SettingsDest.Tunnel -> SettingsTunnelPage(t, settings)
                    SettingsDest.BridgeRegistry -> SettingsBridgeRegistryPage(t, settings)
                    SettingsDest.ServiceConfig -> SettingsServiceConfigPage(t, settings)
                    else -> dest = SettingsDest.Root
                }
            }
        }
        return
    }

    PageScroll {
        Text(t.settings, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        SettingsItem(Icons.Default.Cloud, t.tunnel, onClick = { dest = SettingsDest.Tunnel })
        SettingsItem(Icons.Default.Link, t.bridges, onClick = { dest = SettingsDest.BridgeRegistry })
        SettingsItem(Icons.Default.Settings, t.service, onClick = { dest = SettingsDest.ServiceConfig })
    }
}

@Composable
private fun SettingsItem(icon: ImageVector, title: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Text(title, fontWeight = FontWeight.SemiBold)
        }
    }
}
