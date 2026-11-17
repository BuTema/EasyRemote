package com.artem.easyremote.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.artem.easyremote.AppVersion
import com.artem.easyremote.AppViewModel
import com.artem.easyremote.UpdateInfo
import com.artem.easyremote.UpdateState
import java.text.DateFormat
import java.util.Date

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // no browser: nothing sensible to do
    }
}

/** A thin bar on the PC list when a newer build is available (the user can close it for that build). */
@Composable
fun UpdateBanner(vm: AppViewModel) {
    if (!vm.updater.supported) return
    val state by vm.updater.state.collectAsState()
    val dismissed by vm.settings.dismissedUpdate.collectAsState()
    val info = (state as? UpdateState.Available)?.info ?: return
    if (info.sha == dismissed) return
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("A new version is available", style = MaterialTheme.typography.titleSmall)
                if (info.title.isNotBlank()) Text(info.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            TextButton(onClick = { openUrl(context, info.apkUrl) }) { Text("Download") }
            IconButton(onClick = { vm.settings.dismissUpdate(info.sha) }) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

/** Version number and, in the GitHub build, the update check (manual button + automatic switch). */
@Composable
fun AboutSection(vm: AppViewModel) {
    val context = LocalContext.current
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
        (state as? UpdateState.Available)?.info?.let { info -> AvailableCard(info) { openUrl(context, info.apkUrl) } }
    }
}

@Composable
private fun AvailableCard(info: UpdateInfo, onDownload: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("New build ${info.sha.take(7)}", style = MaterialTheme.typography.titleSmall)
            if (info.title.isNotBlank()) Text(info.title, style = MaterialTheme.typography.bodySmall)
            Text(
                "Download it, then open the downloaded file to install over this one. Your PCs stay paired.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onDownload) { Text("Download") }
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
