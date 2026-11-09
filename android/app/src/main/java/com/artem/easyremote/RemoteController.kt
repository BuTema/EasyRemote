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

    // Pointer movement and scrolling are coalesced, never dropped: the pending deltas are summed, so
    // the total displacement is exact. A frame goes out immediately whenever the link is idle (no
    // added latency) and at most every MIN_FRAME_MS (250 per second). While the network is behind
    // (bytes still queued in the socket) new movement keeps adding up in the pending sum instead of
    // piling up as stale frames, so the cursor never keeps running after the finger has stopped.
    private var pendingDx = 0
    private var pendingDy = 0
    private var moveScheduled = false
    private var lastMoveSent = 0L
    private var pendingSx = 0
    private var pendingSy = 0
    private var scrollScheduled = false
    private var lastScrollSent = 0L

    private val moveFlush = Runnable { moveScheduled = false; pumpMove() }
    private val scrollFlush = Runnable { scrollScheduled = false; pumpScroll() }

    private fun linkBusy(): Boolean = (session?.queuedBytes() ?: 0L) > BACKLOG_BYTES

    /** Sends the pending movement now, unconditionally. */
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

    /** Sends if the rate limit and the link allow it, otherwise tries again shortly. */
    private fun pumpMove() {
        if (pendingDx == 0 && pendingDy == 0) return
        val wait = lastMoveSent + MIN_FRAME_MS - SystemClock.uptimeMillis()
        if (wait <= 0 && !linkBusy()) {
            flushMove()
        } else if (!moveScheduled) {
            moveScheduled = true
            handler.postDelayed(moveFlush, if (wait > 0) wait else MIN_FRAME_MS)
        }
    }

    private fun pumpScroll() {
        if (pendingSx == 0 && pendingSy == 0) return
        val wait = lastScrollSent + MIN_FRAME_MS - SystemClock.uptimeMillis()
        if (wait <= 0 && !linkBusy()) {
            flushScroll()
        } else if (!scrollScheduled) {
            scrollScheduled = true
            handler.postDelayed(scrollFlush, if (wait > 0) wait else MIN_FRAME_MS)
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
        pumpMove()
    }

    /** Hi-res wheel units (120 = one notch); +dy up, +dx right. */
    fun scroll(dx: Int, dy: Int) {
        if (session == null) return
        pendingSx += dx
        pendingSy += dy
        pumpScroll()
    }

    /** The finger was lifted: send whatever is still pending right away so the cursor stops promptly. */
    fun flushPointer() {
        flushMove()
        flushScroll()
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
        private const val MIN_FRAME_MS = 4L
        private const val BACKLOG_BYTES = 256L
        private const val MAX_FAILURES = 5
    }
}
