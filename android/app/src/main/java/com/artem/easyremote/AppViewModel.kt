package com.artem.easyremote

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface Screen {
    data object List : Screen
    data class Remote(val fingerprint: String) : Screen
    data object Add : Screen
    data object Settings : Screen
}

sealed interface PairUi {
    data object Idle : PairUi
    data object Working : PairUi
    data class Error(val message: String) : PairUi
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = PcStore(app)
    val settings = SettingsStore(app)
    val discovery = Discovery(app)
    val paywall: Paywall = createPaywall(app)

    private val _pcs = MutableStateFlow(store.all())
    val pcs: StateFlow<List<Pc>> = _pcs

    private val _screen = MutableStateFlow<Screen>(Screen.List)
    val screen: StateFlow<Screen> = _screen

    private val _pair = MutableStateFlow<PairUi>(PairUi.Idle)
    val pair: StateFlow<PairUi> = _pair

    private val http = OkHttpClient.Builder()
        .socketFactory(NoDelaySocketFactory())
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WebSocket: no read timeout, liveness is checked with pings
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    val controller = RemoteController(viewModelScope, http, store, discovery) { _pcs.value = store.all() }

    // ------------------------------------------------------------------ reachability

    private val _online = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Fingerprints of the PCs that answered a quick TCP connect to their agent port. mDNS alone is
     * a poor "is it there" signal: Android drops and misses its announcements, so a PC that was
     * connected a second ago would show as unseen. This probe is what the list's status is based on.
     */
    val online: StateFlow<Set<String>> = _online
    private var probeJob: Job? = null

    private fun startProbing() {
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            while (true) {
                if (_screen.value is Screen.List) {
                    val pcs = store.all()
                    val up = coroutineScope {
                        pcs.map { pc -> async { if (reachable(pc)) pc.fingerprint else null } }.awaitAll()
                    }.filterNotNull().toSet()
                    _online.value = up
                }
                delay(if (_online.value.size < store.all().size) 4_000 else 10_000)
            }
        }
    }

    private fun stopProbing() {
        probeJob?.cancel()
        probeJob = null
    }

    private suspend fun reachable(pc: Pc): Boolean = withContext(Dispatchers.IO) {
        val targets = buildList {
            discovery.found.value[pc.shortId]?.let { add(it.host to it.port) }
            add(pc.host to pc.port)
        }.distinct()
        targets.any { (host, port) ->
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 1_200); true }
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun refresh() {
        _pcs.value = store.all()
    }

    // ------------------------------------------------------------------ navigation

    fun openRemote(pc: Pc) {
        _screen.value = Screen.Remote(pc.fingerprint)
        controller.start(pc)
    }

    fun showAdd() {
        paywall.refresh()
        _pair.value = PairUi.Idle
        _screen.value = Screen.Add
    }

    fun showSettings() {
        _screen.value = Screen.Settings
    }

    fun back() {
        val leaving = _screen.value as? Screen.Remote
        if (leaving != null) {
            // it was connected a moment ago: say so right away, the probe confirms within seconds
            if (controller.state.value == ConnState.Ready) _online.value = _online.value + leaving.fingerprint
            controller.stop()
        }
        _pair.value = PairUi.Idle
        _screen.value = Screen.List
    }

    fun forget(pc: Pc) {
        store.remove(pc.fingerprint)
        refresh()
        if ((_screen.value as? Screen.Remote)?.fingerprint == pc.fingerprint) back()
    }

    /** Activity came to the foreground. */
    fun onForeground() {
        discovery.start()
        startProbing()
        if (_screen.value is Screen.Add) paywall.refresh() // e.g. back from the purchase sheet
        val s = _screen.value
        if (s is Screen.Remote) {
            store.all().firstOrNull { it.fingerprint == s.fingerprint }?.let { controller.start(it) }
        }
    }

    /** Activity left the foreground: stop everything that uses the radio. */
    fun onBackground() {
        discovery.stop()
        stopProbing()
        controller.stop()
    }

    /** Phone volume buttons control the PC while a remote screen is connected. */
    fun interceptVolumeKeys(): Boolean =
        _screen.value is Screen.Remote && controller.state.value == ConnState.Ready && settings.volumeKeys.value

    // ------------------------------------------------------------------ pairing

    fun pairFromLink(text: String) {
        val link = parsePairLink(text)
        if (link == null) {
            _pair.value = PairUi.Error("That is not an EasyRemote pairing code.")
            return
        }
        startPair(link.host, link.port, link.secret, link.fingerprint)
    }

    /** [address] is `host` or `host:port`. The code is the one printed by `easyremote pair`. */
    fun pairManual(address: String, code: String) {
        val trimmed = address.trim()
        val host: String
        val port: Int
        val idx = trimmed.lastIndexOf(':')
        if (idx > 0 && trimmed.count { it == ':' } == 1) {
            host = trimmed.substring(0, idx)
            port = trimmed.substring(idx + 1).toIntOrNull() ?: run {
                _pair.value = PairUi.Error("The port must be a number.")
                return
            }
        } else {
            host = trimmed
            port = DEFAULT_PORT
        }
        if (host.isBlank()) {
            _pair.value = PairUi.Error("Enter the PC's address.")
            return
        }
        if (Pairing.normalize(code).length < 16) {
            _pair.value = PairUi.Error("The code has 16 characters, like ABCD-EFGH-IJKL-MNOP.")
            return
        }
        startPair(host, port, code, null)
    }

    fun pairError(message: String) {
        _pair.value = PairUi.Error(message)
    }

    private fun startPair(host: String, port: Int, secret: String, pinned: String?) {
        if (_pair.value == PairUi.Working) return
        val wall = paywall.state.value
        val before = _pcs.value
        if (PairPolicy.needsCredit(wall.enabled, before, pinned) && wall.credits < 1) {
            _pair.value = PairUi.Error(
                "Your first PC is free. Each additional PC needs a one-time purchase" +
                    (wall.price?.let { " ($it)" } ?: "") + ". Buy it with the button above, then pair again."
            )
            return
        }
        _pair.value = PairUi.Working
        viewModelScope.launch {
            val session = WsSession(http)
            val out = session.open(host, port, pinned) { nonce, seen ->
                JSONObject()
                    .put("t", "pair")
                    .put("v", Proto.VERSION)
                    .put("device", Build.MODEL ?: "Android")
                    .put("proof", Pairing.proof(secret, nonce, seen))
                    .toString()
            }
            session.close()
            when (out) {
                is Outcome.Ready -> {
                    val p = out.paired
                    if (p == null || out.seenFingerprint.isEmpty()) {
                        _pair.value = PairUi.Error("The PC did not complete pairing.")
                    } else {
                        val pc = Pc(out.seenFingerprint, p.name, host, port, p.id, p.token)
                        // spend a credit only if this really was an additional PC
                        val charged = PairPolicy.needsCredit(wall.enabled, before, pc.fingerprint)
                        store.save(pc)
                        refresh()
                        if (charged) paywall.consumeOne()
                        _pair.value = PairUi.Idle
                        openRemote(pc)
                    }
                }
                is Outcome.Rejected -> _pair.value = PairUi.Error(
                    when (out.code) {
                        "no_pairing_window" -> "Pairing is not open on the PC. Run “easyremote pair” there and try again."
                        "pairing_failed" -> "Wrong or expired code. Run “easyremote pair” again for a new one."
                        else -> out.message.ifBlank { out.code }
                    }
                )
                is Outcome.Failed -> _pair.value = PairUi.Error(
                    if (out.certMismatch) {
                        "The PC's certificate does not match the QR code. Generate a new code and try again."
                    } else {
                        "Could not reach the PC at $host:$port (${out.error}). Is the EasyRemote agent running, " +
                            "and are both devices on the same network?"
                    }
                )
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 47821
    }
}
