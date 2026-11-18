package com.artem.easyremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artem.easyremote.AppVersion
import com.artem.easyremote.AppViewModel
import com.artem.easyremote.UpdateInfo
import com.artem.easyremote.UpdateState
import com.artem.easyremote.versionNameFor
import java.text.DateFormat
import java.util.Date

/** A card under the top bar of the PC list when a newer build is available (can be postponed for that build). */
@Composable
fun UpdateBanner(vm: AppViewModel) {
    if (!vm.updater.supported) return
    val state by vm.updater.state.collectAsState()
    val dismissed by vm.settings.dismissedUpdate.collectAsState()
    val info = (state as? UpdateState.Available)?.info ?: return
    if (info.sha == dismissed) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                "A new version is available" + (info.number?.let { " (${versionNameFor(it)})" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
            if (info.title.isNotBlank()) {
                Text(info.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { vm.settings.dismissUpdate(info.sha) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                ) { Text("Later") }
                Button(onClick = { vm.updater.install(info) }) { Text("Update") }
            }
        }
    }
}

/** Version number and, in the GitHub build, the update check (manual button + automatic switch). */
@Composable
fun AboutSection(vm: AppViewModel) {
    val updater = vm.updater
    val state by updater.state.collectAsState()
    val auto by vm.settings.autoUpdateCheck.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text("Version", style = MaterialTheme.typography.titleMedium)
            Text("${AppVersion.name} (build ${AppVersion.shortSha})", style = MaterialTheme.typography.bodySmall)
        }
        if (!updater.supported) {
            Text("Updates come from Google Play.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        SwitchRow("Check for updates automatically", "At most twice a day, when the app is opened", auto) { vm.settings.setAutoUpdateCheck(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { updater.check() }, enabled = state !is UpdateState.Checking) { Text("Check for updates") }
            Text(statusText(state), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        (state as? UpdateState.Available)?.info?.let { info -> AvailableCard(info) { updater.install(info) } }
    }
}

@Composable
private fun AvailableCard(info: UpdateInfo, onDownload: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("New version" + (info.number?.let { " ${versionNameFor(it)}" } ?: " ${info.sha.take(7)}"), style = MaterialTheme.typography.titleSmall)
            if (info.title.isNotBlank()) Text(info.title, style = MaterialTheme.typography.bodySmall)
            Text(
                "The update is downloaded here and then installed over this version. Your PCs stay paired.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onDownload) { Text("Update") }
        }
    }
}

private fun statusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> if (AppVersion.isDevBuild) "Development build" else ""
    UpdateState.Checking -> "Checking…"
    is UpdateState.UpToDate -> "You have the latest version (checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.checkedAtMs))})"
    is UpdateState.Available -> "A new version is available"
    is UpdateState.Failed -> "Could not check: ${state.message}"
}
