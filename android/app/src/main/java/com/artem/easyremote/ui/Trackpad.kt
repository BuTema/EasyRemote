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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artem.easyremote.Proto
import com.artem.easyremote.RemoteController
import com.artem.easyremote.StraightFilter
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val TAP_MAX_MS = 220L
private const val DOUBLE_TAP_MS = 280L

/** PC pixels per dp of finger travel at speed 1.0 (before acceleration). */
private const val PC_PX_PER_DP = 3f

/** Time constant of the finger-speed smoothing (ms): long enough to hide event-timing jitter. */
private const val SPEED_TAU_MS = 30f

/** Hi-res wheel units per dp of two-finger travel (120 units = one wheel notch). */
private const val WHEEL_UNITS_PER_DP = 10f

/**
 * Touchpad + left/right buttons.
 *  - one finger: move pointer
 *  - tap: left click; tap and immediately press again: double click, or hold and move to drag
 *  - two fingers: scroll (both axes); two-finger tap: right click
 */
@Composable
fun Trackpad(
    c: RemoteController,
    speed: () -> Float,
    natural: () -> Boolean,
    straight: () -> Boolean = { true },
    modifier: Modifier = Modifier,
    /** fixed pad height (portrait, where the pad hugs the bottom); null = take all remaining height */
    padHeight: Dp? = null,
) {
    val speedNow by rememberUpdatedState(speed)
    val naturalNow by rememberUpdatedState(natural)
    val straightNow by rememberUpdatedState(straight)
    Column(modifier) {
        Box(
            (if (padHeight != null) Modifier.height(padHeight) else Modifier.weight(1f))
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = "Touchpad" }
                .pointerInput(Unit) { trackpadGestures(c, { speedNow() }, { naturalNow() }, { straightNow() }) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Touchpad",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClickButton("Left click", "Left", Proto.BTN_LEFT, c, Modifier.weight(1f).fillMaxHeight())
            ClickButton("Right click", "Right", Proto.BTN_RIGHT, c, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ClickButton(description: String, label: String, button: Int, c: RemoteController, modifier: Modifier) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) scheme.primary else scheme.secondaryContainer)
            .semantics { contentDescription = description; role = Role.Button }
            .pointerInput(button) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    c.button(button, Proto.ACT_PRESS)
                    waitForUpOrCancellation()
                    c.button(button, Proto.ACT_RELEASE)
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed) scheme.onPrimary else scheme.onSecondaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private suspend fun PointerInputScope.trackpadGestures(
    c: RemoteController,
    speed: () -> Float,
    natural: () -> Boolean,
    straight: () -> Boolean,
) {
    var lastTapUp = 0L // uptime of the previous tap, for tap-and-drag / double click

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val t0 = down.uptimeMillis
        val dragMode = lastTapUp != 0L && t0 - lastTapUp < DOUBLE_TAP_MS
        lastTapUp = 0L
        val slop = viewConfiguration.touchSlop

        var total = Offset.Zero // single-finger travel, to tell a tap from a move
        var moved = false
        var twoFinger = false
        var twoTravel = 0f
        var scrolled = false
        var remX = 0f
        var remY = 0f
        var scrollRemX = 0f
        var scrollRemY = 0f
        var buttonHeld = false
        var lastTime = t0
        var speedDpMs = 0f // smoothed finger speed
        val straightFilter = StraightFilter()

        if (dragMode) {
            c.button(Proto.BTN_LEFT, Proto.ACT_PRESS)
            buttonHeld = true
        }

        while (true) {
            val event = awaitPointerEvent()
            lastTime = event.changes.maxOf { it.uptimeMillis }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                // ---- two fingers: scroll
                twoFinger = true
                val avg = pressed.fold(Offset.Zero) { acc, ch -> acc + ch.positionChange() } / pressed.size.toFloat()
                twoTravel += avg.getDistance()
                if (scrolled || twoTravel > slop) {
                    scrolled = true
                    val dxDp = avg.x / density
                    val dyDp = avg.y / density
                    // natural: fingers moving down drag the page down, i.e. wheel up (+)
                    val sign = if (natural()) 1f else -1f
                    scrollRemY += sign * dyDp * WHEEL_UNITS_PER_DP
                    scrollRemX += -sign * dxDp * WHEEL_UNITS_PER_DP
                    val ix = scrollRemX.toInt()
                    val iy = scrollRemY.toInt()
                    scrollRemX -= ix
                    scrollRemY -= iy
                    if (ix != 0 || iy != 0) c.scroll(ix, iy)
                }
            } else if (!twoFinger) {
                // ---- one finger: move pointer
                val ch = pressed[0]
                val delta = ch.positionChange()
                total += delta
                if (!moved && total.getDistance() > slop) moved = true
                if (moved || dragMode) {
                    val dtMs = max(1L, ch.uptimeMillis - ch.previousUptimeMillis).toFloat()
                    val dxDp = delta.x / density
                    val dyDp = delta.y / density
                    // Acceleration follows a smoothed speed. The raw per-event speed (distance / the
                    // time since the previous event) is noisy, because events arrive in uneven
                    // intervals, and used directly it made the gain, and so the cursor, jump.
                    val alpha = 1f - exp(-dtMs / SPEED_TAU_MS)
                    speedDpMs += alpha * (hypot(dxDp, dyDp) / dtMs - speedDpMs)
                    val gain = PC_PX_PER_DP * speed() * (1f + min(speedDpMs / 0.6f, 1.5f))
                    // straight-line assist: flatten small sideways wobble in straight strokes
                    var fx = dxDp
                    var fy = dyDp
                    if (straight()) {
                        straightFilter.apply(dxDp, dyDp, dtMs)
                        fx = straightFilter.outX
                        fy = straightFilter.outY
                    }
                    remX += fx * gain
                    remY += fy * gain
                    val ix = remX.toInt()
                    val iy = remY.toInt()
                    remX -= ix
                    remY -= iy
                    if (ix != 0 || iy != 0) c.move(ix, iy)
                }
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }

        c.flushPointer() // finger up: send what is pending now, the cursor stops with the finger
        val duration = lastTime - t0
        when {
            buttonHeld -> c.button(Proto.BTN_LEFT, Proto.ACT_RELEASE)
            twoFinger -> if (!scrolled && twoTravel < slop * 2f && duration < TAP_MAX_MS + 100) {
                c.click(Proto.BTN_RIGHT)
            }
            !moved && duration < TAP_MAX_MS -> {
                c.click(Proto.BTN_LEFT)
                lastTapUp = lastTime
            }
            else -> {}
        }
    }
}
