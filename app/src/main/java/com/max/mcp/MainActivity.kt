package com.max.mcp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.max.mcp.core.SettingsStore
import com.max.mcp.service.McpForegroundService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { McpLiteApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpLiteApp() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val t = UiText(zh = settings.languageZh)
    var tab by remember { mutableStateOf(MainTab.Service) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Cloud, null) },
                    label = { Text(t.state) },
                    selected = tab == MainTab.Service,
                    onClick = { tab = MainTab.Service }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Link, null) },
                    label = { Text(t.bridges) },
                    selected = tab == MainTab.Bridges,
                    onClick = { tab = MainTab.Bridges }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text(t.settings) },
                    selected = tab == MainTab.Settings,
                    onClick = { tab = MainTab.Settings }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                MainTab.Service -> LiteServiceTab(settings, t)
                MainTab.Bridges -> SettingsBridgeRegistryPage(t, settings)
                MainTab.Settings -> SettingsHub(t, settings)
            }
        }
    }
}

@Composable
fun LiteServiceTab(settings: SettingsStore, t: UiText) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var tunnelUrl by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(settings.mcpPort.toString()) }

    LaunchedEffect(Unit) {
        while (true) {
            running = McpForegroundService.currentServer != null
            tunnelUrl = McpForegroundService.currentServer?.tunnel?.status()?.publicUrl ?: ""
            delay(2000)
        }
    }

    PageScroll {
        Text(t.appTitle, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        GlassGroup {
            Column(Modifier.padding(16.dp)) {
                Text(t.state + ": " + if (running) t.running else t.stopped,
                     style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Port: " + port, style = MaterialTheme.typography.bodyMedium)
                if (tunnelUrl.isNotBlank()) {
                    Text("Tunnel: " + tunnelUrl, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (running) McpForegroundService.stop(context)
                        else McpForegroundService.start(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (running) t.stopped else t.running) }
            }
        }
    }
}
