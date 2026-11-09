package com.artem.easyremote

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MuteFrameTest {
    @Test fun toggleHasNoPayload() = assertArrayEquals(byteArrayOf(0x06), Proto.mute())
    @Test fun setMuteCarriesTheState() {
        assertArrayEquals(byteArrayOf(0x06, 1), Proto.muteSet(true))
        assertArrayEquals(byteArrayOf(0x06, 0), Proto.muteSet(false))
    }
}
