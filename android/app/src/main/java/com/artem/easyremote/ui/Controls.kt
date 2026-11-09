package com.artem.easyremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.VolumeMute
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
import androidx.compose.ui.graphics.Color
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
    /** red background (used by Mute while the PC is muted) */
    danger: Boolean = false,
    repeat: Boolean = false,
    repeatMs: Long = 90L,
    /** custom content drawn in the foreground colour instead of [icon] / [label] */
    content: (@Composable (fg: Color) -> Unit)? = null,
    onPress: () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        danger -> if (pressed) Color(0xFFB71C1C) else Color(0xFFD32F2F)
        pressed -> scheme.primary
        active -> scheme.tertiaryContainer
        else -> scheme.secondaryContainer
    }
    val fg = when {
        danger -> Color.White
        pressed -> scheme.onPrimary
        active -> scheme.onTertiaryContainer
        else -> scheme.onSecondaryContainer
    }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
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
        if (content != null) content(fg)
        if (icon != null) Icon(icon, contentDescription = null, tint = fg)
        if (label != null) Text(label, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

private val CELL_HEIGHT = 50.dp
private val CELL_GAP = 6.dp
private val GROUP_GAP = 12.dp

/**
 * The control panel: navigation on the left, media on the right, both as two aligned rows.
 *
 *   Esc  .   Up   .  Enter   |  Play/Pause  Fullscreen  Keyboard
 *    .  Left Down Right  .    |  Vol -       Mute        Vol +
 *
 * Narrow panes (landscape) stack the two groups instead of putting them side by side.
 */
@Composable
fun Controls(
    c: RemoteController,
    volume: VolumeInfo?,
    onKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
    if (stacked) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
            NavGroup(c, Modifier.fillMaxWidth())
            MediaGroup(c, volume, onKeyboard, Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
            NavGroup(c, Modifier.weight(5f))
            MediaGroup(c, volume, onKeyboard, Modifier.weight(3f))
        }
    }
}

/** One cell of a grid row: a button, or an empty spacer when [content] is null (not focusable). */
@Composable
private fun RowScope.Cell(content: (@Composable (Modifier) -> Unit)?) {
    val m = Modifier.weight(1f).height(CELL_HEIGHT)
    if (content != null) content(m) else Spacer(m)
}

@Composable
private fun NavGroup(c: RemoteController, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            Cell { PadButton("Escape", it, label = "Esc") { c.key(Proto.KEY_ESC) } }
            Cell(null)
            Cell { PadButton("Up", it, icon = Icons.Default.KeyboardArrowUp, repeat = true) { c.key(Proto.KEY_UP) } }
            Cell(null)
            Cell { PadButton("Enter", it, icon = Icons.Default.KeyboardReturn) { c.key(Proto.KEY_ENTER) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            Cell(null)
            Cell { PadButton("Left", it, icon = Icons.Default.KeyboardArrowLeft, repeat = true) { c.key(Proto.KEY_LEFT) } }
            Cell { PadButton("Down", it, icon = Icons.Default.KeyboardArrowDown, repeat = true) { c.key(Proto.KEY_DOWN) } }
            Cell { PadButton("Right", it, icon = Icons.Default.KeyboardArrowRight, repeat = true) { c.key(Proto.KEY_RIGHT) } }
            Cell(null)
        }
    }
}

@Composable
private fun MediaGroup(c: RemoteController, volume: VolumeInfo?, onKeyboard: () -> Unit, modifier: Modifier) {
    val muted = volume?.muted == true
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            Cell {
                PadButton("Play or pause", it, content = { fg ->
                    // both symbols, side by side, in the one button
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                        Icon(Icons.Default.Pause, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                    }
                }) { c.media(Proto.MEDIA_PLAY_PAUSE) }
            }
            // sends the ordinary letter F (not F11): most players use F for fullscreen
            Cell { PadButton("Fullscreen, sends F", it, icon = Icons.Default.Fullscreen) { c.key(Proto.KEY_F) } }
            Cell { PadButton("Open keyboard", it, icon = Icons.Default.Keyboard) { onKeyboard() } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            Cell {
                PadButton("Volume down", it, repeat = true, repeatMs = 160, content = { fg -> SpeakerWithSign(Icons.Default.Remove, fg) }) {
                    c.volumeSteps(-1)
                }
            }
            Cell {
                // the state shown here is the one the PC reported, not a guess made on the phone
                PadButton(
                    description = if (muted) "Unmute" else "Mute",
                    modifier = it,
                    icon = if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    danger = muted,
                ) { c.toggleMute() }
            }
            Cell {
                PadButton("Volume up", it, repeat = true, repeatMs = 160, content = { fg -> SpeakerWithSign(Icons.Default.Add, fg) }) {
                    c.volumeSteps(1)
                }
            }
        }
    }
}

/** A small speaker with a clearly visible minus or plus next to it. */
@Composable
private fun SpeakerWithSign(sign: ImageVector, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.VolumeMute, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Icon(sign, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}
