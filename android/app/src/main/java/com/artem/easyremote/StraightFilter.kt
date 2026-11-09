package com.artem.easyremote

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Straight-line assist for the touchpad.
 *
 * A finger never travels in a perfectly straight line: it wobbles a little sideways while moving
 * ahead, and at pointer scale that wobble shows up as one-pixel steps back and forth across the
 * line. This filter follows the direction the finger has been travelling in (smoothed over about
 * 60 ms) and, only while the motion is clearly a straight stroke, low-passes the sideways part of
 * each movement (about 45 ms), so quick wobble is flattened and deliberate steering (a sustained
 * sideways movement) gets through. The forward part is never touched, so speed and distance are
 * unchanged. It stays out of the way for slow movement, for turns (a sideways component that is
 * large compared with the forward one) and for curves (the direction estimate follows them).
 *
 * Feed it every movement in dp with the time since the previous one; read the result from
 * [outX] / [outY]. Tuned with a prototype against straight, noisy, turning and curved strokes.
 */
class StraightFilter {
    var outX = 0f
        private set
    var outY = 0f
        private set

    // smoothed velocity (dp per ms) and speed
    private var sx = 0f
    private var sy = 0f
    private var mag = 0f

    // low-passed sideways velocity
    private var lat = 0f

    fun reset() {
        sx = 0f; sy = 0f; mag = 0f; lat = 0f
        outX = 0f; outY = 0f
    }

    fun apply(dx: Float, dy: Float, dtMs: Float) {
        val dt = min(40f, max(1f, dtMs))
        val vx = dx / dt
        val vy = dy / dt
        val speed = hypot(vx, vy)

        val a = 1f - exp(-dt / DIR_TAU_MS)
        sx += a * (vx - sx)
        sy += a * (vy - sy)
        mag += a * (speed - mag)
        val sm = hypot(sx, sy)
        if (sm < MIN_SPEED || mag <= 1e-6f) {
            // too slow to tell a direction: pass through untouched
            lat = 0f
            outX = dx; outY = dy
            return
        }
        val ux = sx / sm
        val uy = sy / sm
        val nx = -uy
        val ny = ux
        val par = vx * ux + vy * uy
        val latIn = vx * nx + vy * ny

        // how straight the recent stroke was: 1 = every movement points the same way
        val coherence = min(1f, sm / mag)
        var w = smoothstep(0.75f, 0.95f, coherence)
        // a large sideways part next to the forward part is a turn, not a wobble
        val ratio = abs(latIn) / max(abs(par), 1e-4f)
        w *= 1f - smoothstep(0.30f, 0.55f, ratio)

        val b = 1f - exp(-dt / LAT_TAU_MS)
        lat += b * (latIn - lat)
        val latOut = latIn + w * (lat - latIn)

        outX = (par * ux + latOut * nx) * dt
        outY = (par * uy + latOut * ny) * dt
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = min(1f, max(0f, (x - e0) / (e1 - e0)))
        return t * t * (3f - 2f * t)
    }

    companion object {
        const val DIR_TAU_MS = 60f
        const val LAT_TAU_MS = 45f
        /** dp per ms below which no direction is assumed (40 dp per second) */
        const val MIN_SPEED = 0.04f
    }
}
