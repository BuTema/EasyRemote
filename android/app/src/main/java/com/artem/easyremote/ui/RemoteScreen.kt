package com.artem.easyremote.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.artem.easyremote.AppViewModel
import com.artem.easyremote.ConnState
import com.artem.easyremote.FailKind
import com.artem.easyremote.Pc
import com.artem.easyremote.VolumeInfo
import com.artem.easyremote.TouchMode

/**
 * Remote control screen.
 * Portrait: controls on top, touchpad at the bottom. Landscape: controls left, touchpad right.
 */
@Composable
fun RemoteScreen(vm: AppViewModel, pc: Pc) {
    val c = vm.controller
    val state by c.state.collectAsState()
    val volume by c.volume.collectAsState()
    val agentBuild by c.agentBuild.collectAsState()
    val natural by vm.settings.naturalScroll.collectAsState()
    val straight by vm.settings.straightAssist.collectAsState()
    val speed by vm.settings.pointerSpeed.collectAsState()
    val keepOn by vm.settings.keepScreenOn.collectAsState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var keyboardRequest by remember { mutableIntStateOf(0) }

    // While the remote is open keep Wi-Fi out of power-save mode: otherwise the radio sleeps between
    // bursts and delivers the cursor movement in lumps, which shows as jumpy motion.
    val appContext = LocalContext.current.applicationContext
    val foreground by vm.inForeground.collectAsState()
    val ready = state is ConnState.Ready
    // only while the screen is on and in front: with the player running the connection outlives the screen
    val active = ready && foreground
    DisposableEffect(active) {
        val lock = if (active) lowLatencyWifiLock(appContext) else null
        // and take touch samples as they happen instead of once per screen refresh
        TouchMode.unbuffered = active
        onDispose {
            TouchMode.unbuffered = false
            runCatching { lock?.release() }
        }
    }

    // Android 13+: the player in the notification shade needs the notification permission; ask once.
    val playerOn by vm.settings.playerNotification.collectAsState()
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(ready, playerOn) {
        if (ready && playerOn && Build.VERSION.SDK_INT >= 33 && !vm.settings.notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            vm.settings.notificationPermissionAsked = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    // Outside a Scaffold nothing sets the text colour, so without this the title and the status
    // text come out black on the dark background.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TopBar(pc.name, onBack = { vm.back() })
        if (ready && agentBuild == null) OutdatedAgentNotice()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val bodyHeight = maxHeight // read here: inside the nested Column the scope marker blocks it
            if (ready) KeyboardCapture(c, keyboardRequest)

            // The control layout is always shown, so it is clear what the screen will look like; until
            // the PC is connected it is dimmed and does not react to touches.
            Box(Modifier.fillMaxSize().alpha(if (ready) 1f else 0.38f)) {
                if (landscape) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Controls(
                            c, volume, stacked = true,
                            status = { ConnStatus(state, stacked = false) },
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        )
                        Trackpad(c, { speed }, { natural }, { straight }, { keyboardRequest++ }, Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    // Flexible empty space on top; controls, touchpad and the click buttons form one
                    // group at the bottom, with small equal gaps and clear of the navigation gestures.
                    Column(
                        Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Controls(c, volume, status = { ConnStatus(state, stacked = false) })
                        Spacer(Modifier.height(8.dp))
                        Trackpad(
                            c, { speed }, { natural }, { straight }, { keyboardRequest++ }, Modifier.fillMaxWidth(),
                            padHeight = touchpadHeightDp(bodyHeight.value).dp,
                        )
                    }
                }
            }
            if (!ready) {
                // swallows every touch, so the dimmed buttons below cannot be pressed
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        },
                )
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    ConnectionPanel(pc, state, onRetry = { vm.openRemote(pc) }, onForget = { vm.forget(pc) })
                }
            }
        }
    }
    }
}

@Suppress("DEPRECATION")
private fun lowLatencyWifiLock(context: Context): WifiManager.WifiLock? = try {
    val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
    wifi.createWifiLock(mode, "EasyRemote").apply {
        setReferenceCounted(false)
        acquire()
    }
} catch (e: Exception) {
    null
}

@Composable
private fun TopBar(name: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Connection state as a coloured dot and a word. [stacked] puts the word under the dot. */
@Composable
internal fun ConnStatus(state: ConnState, stacked: Boolean) {
    val (color, label) = when (state) {
        is ConnState.Ready -> Color(0xFF2E9E57) to "Connected"
        is ConnState.Connecting -> Color(0xFFE0A100) to "Connecting…"
        is ConnState.Failed -> MaterialTheme.colorScheme.error to "Offline"
        is ConnState.Idle -> MaterialTheme.colorScheme.outline to ""
    }
    if (stacked) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Shown when the PC agent is too old to report its build: it then also lacks recent fixes. */
@Composable
private fun OutdatedAgentNotice() {
    Text(
        "The EasyRemote agent on this PC is out of date, so some buttons may not work (Fullscreen, Mute). " +
            "Install the latest version on the PC, then run:  easyremote autostart on",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
    )
}

@Composable
private fun ConnectionPanel(pc: Pc, state: ConnState, onRetry: () -> Unit, onForget: () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 360.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), RoundedCornerShape(24.dp))
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
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
