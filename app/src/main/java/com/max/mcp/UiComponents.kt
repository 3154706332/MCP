package com.max.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NumberSettingRow(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 1024..65535
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { v -> v.toIntOrNull()?.let { if (it in range) onValueChange(it) } },
            modifier = Modifier.width(100.dp),
            singleLine = true
        )
    }
}

@Composable
fun ChipRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, label ->
                FilterChip(
                    selected = i == selectedIndex,
                    onClick = { onSelect(i) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(text)
    }
}
