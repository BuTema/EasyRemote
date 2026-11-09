package com.artem.easyremote

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StraightFilterTest {
    private val dt = 8f // ms between touch events (~120 Hz)

    /** Runs a stroke through the filter; returns the outputs as (x, y) pairs. */
    private fun run(inputs: List<Pair<Float, Float>>, f: StraightFilter = StraightFilter()): List<Pair<Float, Float>> =
        inputs.map { (x, y) -> f.apply(x, y, dt); f.outX to f.outY }

    private fun noisyStroke(n: Int, speed: Float, angle: Double, noise: Float, seed: Int): List<Pair<Float, Float>> {
        val r = Random(seed)
        val ux = cos(angle).toFloat()
        val uy = sin(angle).toFloat()
        // sideways wobble that does not drift: the noise is the change of a bounded offset
        var off = 0f
        return List(n) {
            val target = (r.nextFloat() - 0.5f) * 2f * noise
            val d = target - off
            off = target
            (ux * speed + -uy * d) to (uy * speed + ux * d)
        }
    }

    /** Sideways travel (sum of |lateral step|) of a path relative to direction (ux, uy). */
    private fun sideways(path: List<Pair<Float, Float>>, angle: Double): Float {
        val nx = -sin(angle).toFloat()
        val ny = cos(angle).toFloat()
        return path.sumOf { (x, y) -> abs(x * nx + y * ny).toDouble() }.toFloat()
    }

    @Test
    fun straightStrokeWithWobbleIsFlattened() {
        val angle = 0.3
        val inputs = noisyStroke(120, 0.5f, angle, 0.25f, 1)
        val out = run(inputs)
        // skip the first events: the direction estimate has to settle
        val before = sideways(inputs.drop(20), angle)
        val after = sideways(out.drop(20), angle)
        assertTrue("sideways $after should be well below $before", after < before * 0.9f)
    }

    @Test
    fun forwardDistanceIsPreserved() {
        val angle = 0.0
        val inputs = noisyStroke(150, 0.6f, angle, 0.2f, 2)
        val out = run(inputs)
        val inX = inputs.sumOf { it.first.toDouble() }
        val outX = out.sumOf { it.first.toDouble() }
        assertEquals(inX, outX, inX * 0.03)
    }

    @Test
    fun slowMovementPassesThroughUnchanged() {
        val inputs = List(60) { (if (it % 2 == 0) 0.04f else -0.02f) to 0.03f } // far below MIN_SPEED
        val out = run(inputs)
        for (i in inputs.indices) {
            assertEquals(inputs[i].first, out[i].first, 1e-5f)
            assertEquals(inputs[i].second, out[i].second, 1e-5f)
        }
    }

    @Test
    fun sharpTurnIsNotHeldBack() {
        val f = StraightFilter()
        // 40 events to the right, then straight down at the same speed
        val right = List(40) { 0.5f to 0f }
        val down = List(40) { 0f to 0.5f }
        run(right, f)
        val out = run(down, f)
        val totalY = out.sumOf { it.second.toDouble() }
        // almost all of the downward travel must still come out (a little lag at the corner is fine)
        assertTrue("down travel $totalY of 20", totalY > 18.0)
        val totalSpeedKept = out.sumOf { hypot(it.first, it.second).toDouble() }
        assertTrue(totalSpeedKept > 18.0)
    }

    @Test
    fun gentleCurveIsKeptWithinFewPercent() {
        val f = StraightFilter()
        // an arc: direction rotates 2 degrees per event at a steady speed
        val inputs = List(90) {
            val a = Math.toRadians(2.0 * it)
            (0.5 * cos(a)).toFloat() to (0.5 * sin(a)).toFloat()
        }
        val out = run(inputs, f)
        val inEndX = inputs.sumOf { it.first.toDouble() }
        val inEndY = inputs.sumOf { it.second.toDouble() }
        val outEndX = out.sumOf { it.first.toDouble() }
        val outEndY = out.sumOf { it.second.toDouble() }
        assertEquals(hypot(inEndX, inEndY), hypot(outEndX, outEndY), 2.0)
        assertEquals(inEndX, outEndX, 2.0)
        assertEquals(inEndY, outEndY, 2.0)
    }

    @Test
    fun resetForgetsDirection() {
        val f = StraightFilter()
        run(List(30) { 0.5f to 0f }, f)
        f.reset()
        f.apply(0f, 0.5f, dt)
        assertEquals(0f, f.outX, 1e-6f)
        assertEquals(0.5f, f.outY, 1e-6f)
    }
}
