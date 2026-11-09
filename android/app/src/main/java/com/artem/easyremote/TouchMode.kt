package com.artem.easyremote

/**
 * Android normally hands touch events to the app once per screen refresh (60 to 120 times a second,
 * several touch samples bundled into each). While the remote screen is open the activity asks for
 * unbuffered delivery instead, so every touch sample arrives when it happens, at the touch panel's
 * own rate (often 240 Hz or more), which is what makes pointer movement fine-grained.
 */
object TouchMode {
    @Volatile
    var unbuffered = false
}
