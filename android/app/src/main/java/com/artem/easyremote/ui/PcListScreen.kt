@file:OptIn(ExperimentalMaterial3Api::class)

package com.artem.easyremote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artem.easyremote.AppViewModel
import com.artem.easyremote.Pc

@Composable
fun PcListScreen(vm: AppViewModel) {
    val pcs by vm.pcs.collectAsState()
    val found by vm.discovery.found.collectAsState()
    var forgetting by remember { mutableStateOf<Pc?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EasyRemote") },
                actions = {
                    IconButton(onClick = { vm.showSettings() }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.showAdd() }) {
                Icon(Icons.Default.Add, contentDescription = "Add PC")
            }
        },
    ) { padding ->
        if (pcs.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(16.dp))
                Text("No PC yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Install the EasyRemote agent on your PC, run  easyremote pair  there, then tap + and scan the QR code.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pcs, key = { it.fingerprint }) { pc ->
                    val nearby = found.containsKey(pc.shortId)
                    PcCard(pc, nearby, onOpen = { vm.openRemote(pc) }, onForget = { forgetting = pc })
                }
            }
        }
    }

    forgetting?.let { pc ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${pc.name}?") },
            text = {
                Text(
                    "This phone will no longer be able to control it. To also revoke it on the PC, run  easyremote devices  and  easyremote revoke <id>  there.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.forget(pc); forgetting = null }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PcCard(pc: Pc, nearby: Boolean, onOpen: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(pc.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).background(if (nearby) Color(0xFF2E9E57) else MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (nearby) "On this network" else "Not seen yet · last ${pc.host}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Forget") }, onClick = { menu = false; onForget() })
                }
            }
        }
    }
}
