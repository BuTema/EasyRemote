package com.artem.easyremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artem.easyremote.Proto
import com.artem.easyremote.RemoteController
import com.artem.easyremote.VolumeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Touch-friendly button that fires on finger-down (no tap delay) and can repeat while held.
 */
@Composable
fun PadButton(
    description: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    active: Boolean = false,
    repeat: Boolean = false,
    repeatMs: Long = 90L,
    onPress: () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        pressed -> scheme.primary
        active -> scheme.tertiaryContainer
        else -> scheme.secondaryContainer
    }
    val fg = when {
        pressed -> scheme.onPrimary
        active -> scheme.onTertiaryContainer
        else -> scheme.onSecondaryContainer
    }
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                onClick(label = description) { current(); true }
            }
            .pointerInput(repeat, repeatMs) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    current()
                    val job = if (repeat) {
                        scope.launch {
                            delay(400)
                            while (true) {
                                current()
                                delay(repeatMs)
                            }
                        }
                    } else {
                        null
                    }
                    waitForUpOrCancellation()
                    job?.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg)
        if (label != null) Text(label, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

/** Arrows + Enter, volume column, and the Esc / play-pause / fullscreen / keyboard row. */
@Composable
fun Controls(
    c: RemoteController,
    volume: VolumeInfo?,
    onKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DPad(c)
            VolumePanel(c, volume)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton("Escape", Modifier.weight(1f).height(52.dp), label = "Esc") { c.key(Proto.KEY_ESC) }
            PadButton("Play or pause", Modifier.weight(1f).height(52.dp), icon = Icons.Default.PlayArrow) {
                c.media(Proto.MEDIA_PLAY_PAUSE)
            }
            PadButton("Fullscreen (F11)", Modifier.weight(1f).height(52.dp), icon = Icons.Default.Fullscreen) {
                c.key(Proto.KEY_F11)
            }
            PadButton("Open keyboard", Modifier.weight(1f).height(52.dp), icon = Icons.Default.Keyboard) { onKeyboard() }
        }
    }
}

@Composable
private fun DPad(c: RemoteController) {
    val size = Modifier.size(56.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(size)
            PadButton("Up", size, icon = Icons.Default.KeyboardArrowUp, repeat = true) { c.key(Proto.KEY_UP) }
            Spacer(size)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PadButton("Left", size, icon = Icons.Default.KeyboardArrowLeft, repeat = true) { c.key(Proto.KEY_LEFT) }
            PadButton("Enter", size, icon = Icons.Default.KeyboardReturn) { c.key(Proto.KEY_ENTER) }
            PadButton("Right", size, icon = Icons.Default.KeyboardArrowRight, repeat = true) { c.key(Proto.KEY_RIGHT) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(size)
            PadButton("Down", size, icon = Icons.Default.KeyboardArrowDown, repeat = true) { c.key(Proto.KEY_DOWN) }
            Spacer(size)
        }
    }
}

@Composable
private fun VolumePanel(c: RemoteController, volume: VolumeInfo?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PadButton("Volume down", Modifier.size(56.dp), icon = Icons.Default.Remove, repeat = true, repeatMs = 160) {
                c.volumeSteps(-1)
            }
            Text(
                text = volume?.let { "${it.percent}%" } ?: "–",
                modifier = Modifier.width(52.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            PadButton("Volume up", Modifier.size(56.dp), icon = Icons.Default.Add, repeat = true, repeatMs = 160) {
                c.volumeSteps(1)
            }
        }
        PadButton(
            description = if (volume?.muted == true) "Unmute" else "Mute",
            modifier = Modifier.fillMaxWidth().height(56.dp),
            icon = if (volume?.muted == true) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
            active = volume?.muted == true,
        ) { c.toggleMute() }
    }
}
