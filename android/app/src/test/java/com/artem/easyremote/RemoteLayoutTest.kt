package com.artem.easyremote

import com.artem.easyremote.ui.OLD_CHROME_DP
import com.artem.easyremote.ui.touchpadHeightDp
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteLayoutTest {
    @Test fun touchpadIsSixtyPercentOfTheOldHeight() {
        val body = 700f
        val old = body - OLD_CHROME_DP
        assertEquals(0.6f * old, touchpadHeightDp(body), 0.001f)
    }

    @Test fun touchpadNeverCollapses() {
        assertEquals(100f, touchpadHeightDp(300f), 0.001f)
    }
}
