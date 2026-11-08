@file:OptIn(ExperimentalMaterial3Api::class)

package com.artem.easyremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artem.easyremote.AppViewModel

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.settings
    val natural by s.naturalScroll.collectAsState()
    val speed by s.pointerSpeed.collectAsState()
    val volKeys by s.volumeKeys.collectAsState()
    val awake by s.keepScreenOn.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column {
                Text("Pointer speed", style = MaterialTheme.typography.titleMedium)
                Slider(value = speed, onValueChange = { s.setPointerSpeed(it) }, valueRange = 0.5f..2.5f)
            }
            SwitchRow("Natural scrolling", "Two fingers up moves the page up, like on a phone", natural) { s.setNaturalScroll(it) }
            SwitchRow("Phone volume buttons control the PC", "While a PC is connected", volKeys) { s.setVolumeKeys(it) }
            SwitchRow("Keep screen on", "Uses more battery; the connection pauses when the screen is off", awake) { s.setKeepScreenOn(it) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
