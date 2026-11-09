package com.artem.easyremote.ui

/**
 * Touchpad height in portrait: 60 % of what the touchpad used to get on the same screen.
 *
 * The old portrait layout gave the touchpad everything that was left after the controls (242 dp),
 * the gaps (10 dp), the Left/Right row (8 + 56 dp) and the vertical padding (16 dp), so on a body
 * area [bodyHeightDp] high its height was `bodyHeightDp - OLD_CHROME_DP`.
 */
const val OLD_CHROME_DP = 242f + 10f + 64f + 16f
const val TOUCHPAD_SCALE = 0.6f
const val MIN_TOUCHPAD_DP = 100f

fun touchpadHeightDp(bodyHeightDp: Float): Float =
    maxOf(MIN_TOUCHPAD_DP, (bodyHeightDp - OLD_CHROME_DP) * TOUCHPAD_SCALE)
