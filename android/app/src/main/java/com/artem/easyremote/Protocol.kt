package com.artem.easyremote

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wire format shared with the PC agent. Keep in sync with PROTOCOL.md and agent/src/proto.rs.
 * All binary frames: 1 opcode byte + big-endian payload.
 */
object Proto {
    const val VERSION = 1

    const val OP_MOVE = 0x01
    const val OP_SCROLL = 0x02
    const val OP_BUTTON = 0x03
    const val OP_KEY = 0x04
    const val OP_VOLUME = 0x05
    const val OP_MUTE = 0x06
    const val OP_MEDIA = 0x07
    const val OP_TEXT = 0x08

    const val BTN_LEFT = 0
    const val BTN_RIGHT = 1
    const val BTN_MIDDLE = 2

    const val ACT_RELEASE = 0
    const val ACT_PRESS = 1
    const val ACT_CLICK = 2

    const val KEY_LEFT = 1
    const val KEY_RIGHT = 2
    const val KEY_UP = 3
    const val KEY_DOWN = 4
    const val KEY_ESC = 5
    const val KEY_ENTER = 6
    const val KEY_BACKSPACE = 7
    const val KEY_SPACE = 8
    const val KEY_TAB = 9
    const val KEY_DELETE = 10
    const val KEY_HOME = 11
    const val KEY_END = 12
    const val KEY_PAGEUP = 13
    const val KEY_PAGEDOWN = 14
    const val KEY_F11 = 15
    const val KEY_F = 16

    const val MEDIA_PLAY_PAUSE = 1
    const val MEDIA_NEXT = 2
    const val MEDIA_PREV = 3
    const val MEDIA_STOP = 4

    /** One volume step = 5 % on the PC. */
    const val MAX_TEXT_BYTES = 256

    private fun s16(v: Int): Int = v.coerceIn(-32768, 32767)

    private fun pair(op: Int, a: Int, b: Int): ByteArray {
        val x = s16(a)
        val y = s16(b)
        return byteArrayOf(
            op.toByte(),
            (x shr 8).toByte(), x.toByte(),
            (y shr 8).toByte(), y.toByte(),
        )
    }

    fun move(dx: Int, dy: Int): ByteArray = pair(OP_MOVE, dx, dy)

    /** Hi-res wheel units: 120 = one notch. +dy scrolls up, +dx scrolls right. */
    fun scroll(dx: Int, dy: Int): ByteArray = pair(OP_SCROLL, dx, dy)

    fun button(button: Int, action: Int): ByteArray =
        byteArrayOf(OP_BUTTON.toByte(), button.toByte(), action.toByte())

    fun key(key: Int, action: Int): ByteArray =
        byteArrayOf(OP_KEY.toByte(), key.toByte(), action.toByte())

    fun volume(steps: Int): ByteArray =
        byteArrayOf(OP_VOLUME.toByte(), steps.coerceIn(-20, 20).toByte())

    fun mute(): ByteArray = byteArrayOf(OP_MUTE.toByte())

    fun media(action: Int): ByteArray = byteArrayOf(OP_MEDIA.toByte(), action.toByte())

    /** Splits [s] into TEXT frames of at most [MAX_TEXT_BYTES] UTF-8 bytes, never inside a code point. */
    fun text(s: String): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        val chunk = StringBuilder()
        var chunkBytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = Character.charCount(cp)
            val piece = s.substring(i, i + len)
            val bytes = piece.toByteArray(Charsets.UTF_8).size
            if (chunkBytes + bytes > MAX_TEXT_BYTES) {
                frames.add(textFrame(chunk.toString()))
                chunk.setLength(0)
                chunkBytes = 0
            }
            chunk.append(piece)
            chunkBytes += bytes
            i += len
        }
        if (chunk.isNotEmpty()) frames.add(textFrame(chunk.toString()))
        return frames
    }

    private fun textFrame(s: String): ByteArray {
        val body = s.toByteArray(Charsets.UTF_8)
        val out = ByteArray(body.size + 1)
        out[0] = OP_TEXT.toByte()
        System.arraycopy(body, 0, out, 1, body.size)
        return out
    }
}

/** One-time pairing code handling. Mirrors agent/src/pairing.rs. */
object Pairing {
    /** Upper-case, drop separators, map look-alike digits (0→O, 1→I, 8→B). */
    fun normalize(code: String): String {
        val sb = StringBuilder()
        for (ch in code) {
            if (ch.isWhitespace() || ch == '-') continue
            sb.append(
                when (val u = ch.uppercaseChar()) {
                    '0' -> 'O'
                    '1' -> 'I'
                    '8' -> 'B'
                    else -> u
                }
            )
        }
        return sb.toString()
    }

    /** `hex(HMAC-SHA256(key = normalised code, msg = "pair|" + nonce + "|" + fingerprint))` */
    fun proof(code: String, nonce: String, fingerprint: String): String {
        val key = normalize(code).toByteArray(Charsets.UTF_8)
        require(key.isNotEmpty()) { "empty pairing code" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val out = mac.doFinal("pair|$nonce|$fingerprint".toByteArray(Charsets.UTF_8))
        return out.toHex()
    }
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15])
    }
    return sb.toString()
}

fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()
