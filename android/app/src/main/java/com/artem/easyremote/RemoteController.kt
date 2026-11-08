package com.artem.easyremote

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject

enum class FailKind { Network, NotPaired, CertChanged }

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val attempt: Int) : ConnState
    data object Ready : ConnState
    data class Failed(val message: String, val kind: FailKind) : ConnState
}

/**
 * Owns the connection to the PC that is currently open on screen: connects, reconnects after
 * short drops, and exposes the input API. All input functions must be called on the main thread
 * (they are, from Compose touch handlers).
 */
class RemoteController(
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val store: PcStore,
    private val discovery: Discovery,
    private val onAddressChanged: () -> Unit,
) {
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    private val _volume = MutableStateFlow<VolumeInfo?>(null)
    val volume: StateFlow<VolumeInfo?> = _volume

    private var job: Job? = null

    @Volatile
    private var session: WsSession? = null

    private val handler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ connection

    fun start(pc: Pc) {
        stop()
        job = scope.launch { connectLoop(pc) }
    }

    fun stop() {
        job?.cancel()
        job = null
        session?.close()
        session = null
        resetPending()
        _state.value = ConnState.Idle
        _volume.value = null
    }

    private class Attempt(
        val outcome: Outcome,
        val session: WsSession,
        val closed: CompletableDeferred<String>,
        val host: String,
        val port: Int,
    )

    private fun candidates(pc: Pc): List<Pair<String, Int>> {
        val list = ArrayList<Pair<String, Int>>()
        discovery.found.value[pc.shortId]?.let { list.add(it.host to it.port) }
        val saved = pc.host to pc.port
        if (saved !in list) list.add(saved)
        return list
    }

    private fun authMessage(pc: Pc): String =
        JSONObject().put("t", "auth").put("v", Proto.VERSION).put("id", pc.deviceId).put("token", pc.token).toString()

    private suspend fun attempt(pc: Pc): Attempt? {
        var last: Attempt? = null
        for ((host, port) in candidates(pc)) {
            val closed = CompletableDeferred<String>()
            val s = WsSession(http)
            s.onState = { vol, mute -> _volume.value = VolumeInfo(vol, mute) }
            s.onClosed = { closed.complete(it) }
            val out = s.open(host, port, pc.fingerprint) { _, _ -> authMessage(pc) }
            val a = Attempt(out, s, closed, host, port)
            if (out !is Outcome.Failed) return a
            s.close()
            last = a
            if (out.certMismatch) return a
        }
        return last
    }

    private suspend fun connectLoop(initial: Pc) {
        var pc = initial
        var failures = 0
        while (true) {
            _state.value = ConnState.Connecting(failures + 1)
            val a = attempt(pc)
            if (a == null) {
                failures++
                if (failures >= MAX_FAILURES) {
                    _state.value = ConnState.Failed("no address known", FailKind.Network)
                    return
                }
                delay(1_000)
                continue
            }
            when (val out = a.outcome) {
                is Outcome.Ready -> {
                    if (a.host != pc.host || a.port != pc.port) {
                        pc = pc.copy(host = a.host, port = a.port)
                        store.updateAddress(pc.fingerprint, a.host, a.port)
                        onAddressChanged()
                    }
                    session = a.session
                    _state.value = ConnState.Ready
                    val t0 = SystemClock.elapsedRealtime()
                    try {
                        a.closed.await() // returns when the connection drops
                    } finally {
                        session = null
                        a.session.close()
                    }
                    resetPending()
                    failures = if (SystemClock.elapsedRealtime() - t0 > 5_000) 0 else failures + 1
                    if (failures >= MAX_FAILURES) {
                        _state.value = ConnState.Failed("The connection keeps dropping.", FailKind.Network)
                        return
                    }
                    delay(700)
                }
                is Outcome.Rejected -> {
                    val kind = if (out.code == "bad_token") FailKind.NotPaired else FailKind.Network
                    _state.value = ConnState.Failed(out.message.ifBlank { out.code }, kind)
                    return
                }
                is Outcome.Failed -> {
                    if (out.certMismatch) {
                        _state.value = ConnState.Failed(out.error, FailKind.CertChanged)
                        return
                    }
                    failures++
                    if (failures >= MAX_FAILURES) {
                        _state.value = ConnState.Failed(out.error, FailKind.Network)
                        return
                    }
                    delay(minOf(failures * 800L, 3_000L))
                }
            }
        }
    }

    // ------------------------------------------------------------------ input

    private fun send(bytes: ByteArray) {
        session?.sendBinary(bytes)
    }

    // Pointer movement and scrolling are coalesced to at most one frame per 8 ms (125 Hz). This
    // keeps traffic and radio wake-ups low while staying smooth.
    private var pendingDx = 0
    private var pendingDy = 0
    private var moveScheduled = false
    private var lastMoveSent = 0L
    private var pendingSx = 0
    private var pendingSy = 0
    private var scrollScheduled = false
    private var lastScrollSent = 0L

    private val moveFlush = Runnable { flushMove() }
    private val scrollFlush = Runnable { flushScroll() }

    private fun flushMove() {
        moveScheduled = false
        handler.removeCallbacks(moveFlush)
        if (pendingDx != 0 || pendingDy != 0) {
            send(Proto.move(pendingDx, pendingDy))
            pendingDx = 0
            pendingDy = 0
            lastMoveSent = SystemClock.uptimeMillis()
        }
    }

    private fun flushScroll() {
        scrollScheduled = false
        handler.removeCallbacks(scrollFlush)
        if (pendingSx != 0 || pendingSy != 0) {
            send(Proto.scroll(pendingSx, pendingSy))
            pendingSx = 0
            pendingSy = 0
            lastScrollSent = SystemClock.uptimeMillis()
        }
    }

    private fun resetPending() {
        handler.removeCallbacks(moveFlush)
        handler.removeCallbacks(scrollFlush)
        pendingDx = 0
        pendingDy = 0
        pendingSx = 0
        pendingSy = 0
        moveScheduled = false
        scrollScheduled = false
    }

    /** Relative pointer movement in PC pixels. */
    fun move(dx: Int, dy: Int) {
        if (session == null) return
        pendingDx += dx
        pendingDy += dy
        if (moveScheduled) return
        val wait = lastMoveSent + FRAME_MS - SystemClock.uptimeMillis()
        if (wait <= 0) {
            flushMove()
        } else {
            moveScheduled = true
            handler.postDelayed(moveFlush, wait)
        }
    }

    /** Hi-res wheel units (120 = one notch); +dy up, +dx right. */
    fun scroll(dx: Int, dy: Int) {
        if (session == null) return
        pendingSx += dx
        pendingSy += dy
        if (scrollScheduled) return
        val wait = lastScrollSent + FRAME_MS - SystemClock.uptimeMillis()
        if (wait <= 0) {
            flushScroll()
        } else {
            scrollScheduled = true
            handler.postDelayed(scrollFlush, wait)
        }
    }

    // Everything below first flushes pending movement so the order of events is preserved.

    fun button(which: Int, action: Int) {
        flushMove(); flushScroll()
        send(Proto.button(which, action))
    }

    fun click(which: Int) = button(which, Proto.ACT_CLICK)

    fun key(key: Int, action: Int = Proto.ACT_CLICK) {
        flushMove(); flushScroll()
        send(Proto.key(key, action))
    }

    fun volumeSteps(steps: Int) {
        send(Proto.volume(steps))
    }

    fun toggleMute() {
        send(Proto.mute())
    }

    fun media(action: Int) {
        send(Proto.media(action))
    }

    fun text(s: String) {
        if (s.isEmpty()) return
        flushMove(); flushScroll()
        for (frame in Proto.text(s)) send(frame)
    }

    companion object {
        private const val FRAME_MS = 8L
        private const val MAX_FAILURES = 5
    }
}
