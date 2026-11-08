package com.artem.easyremote.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artem.easyremote.AppViewModel
import com.artem.easyremote.ConnState
import com.artem.easyremote.FailKind
import com.artem.easyremote.Pc

/**
 * Remote control screen.
 * Portrait: controls on top, touchpad at the bottom. Landscape: controls left, touchpad right.
 */
@Composable
fun RemoteScreen(vm: AppViewModel, pc: Pc) {
    val c = vm.controller
    val state by c.state.collectAsState()
    val volume by c.volume.collectAsState()
    val natural by vm.settings.naturalScroll.collectAsState()
    val speed by vm.settings.pointerSpeed.collectAsState()
    val keepOn by vm.settings.keepScreenOn.collectAsState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var keyboardRequest by remember { mutableIntStateOf(0) }

    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TopBar(pc.name, state, onBack = { vm.back() })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state is ConnState.Ready) {
                KeyboardCapture(c, keyboardRequest)
                if (landscape) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Controls(
                            c, volume, onKeyboard = { keyboardRequest++ },
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        )
                        Trackpad(c, { speed }, { natural }, Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Controls(c, volume, onKeyboard = { keyboardRequest++ })
                        Trackpad(c, { speed }, { natural }, Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp))
                    }
                }
            } else {
                ConnectionPanel(pc, state, onRetry = { vm.openRemote(pc) }, onForget = { vm.forget(pc) })
            }
        }
    }
}

@Composable
private fun TopBar(name: String, state: ConnState, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        val (color, label) = when (state) {
            is ConnState.Ready -> Color(0xFF2E9E57) to "Connected"
            is ConnState.Connecting -> Color(0xFFE0A100) to "Connecting…"
            is ConnState.Failed -> MaterialTheme.colorScheme.error to "Offline"
            is ConnState.Idle -> MaterialTheme.colorScheme.outline to ""
        }
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun ConnectionPanel(pc: Pc, state: ConnState, onRetry: () -> Unit, onForget: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is ConnState.Failed -> {
                val (title, body) = when (state.kind) {
                    FailKind.NotPaired -> "Not paired any more" to
                        "${pc.name} no longer knows this phone. Forget it here, then pair again with  easyremote pair."
                    FailKind.CertChanged -> "The PC's identity changed" to
                        "${pc.name} presented a different security certificate. If you did not reinstall or reset EasyRemote on it, " +
                        "do not continue: someone on the network may be pretending to be your PC."
                    FailKind.Network -> "Can't reach ${pc.name}" to
                        "Make sure the PC is on, the EasyRemote agent is running, and both devices are on the same Wi-Fi.\n(${state.message})"
                }
                Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.size(8.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.size(20.dp))
                if (state.kind == FailKind.Network) {
                    Button(onClick = onRetry) { Text("Try again") }
                } else {
                    OutlinedButton(onClick = onForget) { Text("Forget this PC") }
                }
            }
            else -> {
                CircularProgressIndicator()
                Spacer(Modifier.size(16.dp))
                Text("Connecting to ${pc.name}…", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
