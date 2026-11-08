package com.artem.easyremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These vectors are the same ones asserted in the Rust agent (agent/src/proto.rs, pairing.rs and
 * tests/vectors.rs). If either side changes the wire format, one of the two suites fails.
 */
class ProtocolTest {
    @Test
    fun normalizeMapsLookAlikes() {
        assertEquals("ABCDOI23BABC", Pairing.normalize("abcd-0123 8abc"))
    }

    @Test
    fun proofMatchesRustVector() {
        val proof = Pairing.proof(
            "ABCD-EFGH-IJKL-MNOP",
            "00112233445566778899aabbccddeeff",
            "e8cc7844b35ddb322b2f2d219da198c05a2822e9dae76769b81c777224a73d2a",
        )
        assertEquals(PROOF_VECTOR, proof)
    }

    @Test
    fun frames() {
        assertArrayEquals(byteArrayOf(1, 0, 10, -1, -4), Proto.move(10, -4))
        assertArrayEquals(byteArrayOf(2, 0, 0, -1, -120), Proto.scroll(0, -120))
        assertArrayEquals(byteArrayOf(3, 1, 2), Proto.button(Proto.BTN_RIGHT, Proto.ACT_CLICK))
        assertArrayEquals(byteArrayOf(4, 5, 2), Proto.key(Proto.KEY_ESC, Proto.ACT_CLICK))
        assertArrayEquals(byteArrayOf(5, -1), Proto.volume(-1))
        assertArrayEquals(byteArrayOf(6), Proto.mute())
        assertArrayEquals(byteArrayOf(7, 1), Proto.media(Proto.MEDIA_PLAY_PAUSE))
        // values beyond 16 bit are clamped, not wrapped
        assertArrayEquals(byteArrayOf(1, 127, -1, -128, 0), Proto.move(100_000, -100_000))
    }

    @Test
    fun textIsChunkedOnCodePointBoundaries() {
        val s = "я".repeat(300) + "😀".repeat(100)
        val frames = Proto.text(s)
        assertTrue(frames.all { it.size - 1 <= Proto.MAX_TEXT_BYTES && it[0] == Proto.OP_TEXT.toByte() })
        val joined = frames.joinToString("") { String(it, 1, it.size - 1, Charsets.UTF_8) }
        assertEquals(s, joined)
    }

    companion object {
        const val PROOF_VECTOR = "c599c464693b3e72311dc72500340f4791b413f89a34a7fc483f33b500d57b78"
    }
}
