package com.artem.easyremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Trust decision = certificate fingerprint. No CA is ever consulted.
 * With [pinned] == null (manual pairing) the certificate is accepted but remembered in [seen]; the
 * pairing proof is bound to that fingerprint, so a man in the middle cannot complete pairing.
 */
class PinTrustManager(private val pinned: String?) : X509TrustManager {
    @Volatile
    var seen: String? = null

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not used")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val fp = sha256Hex(leaf.encoded)
        seen = fp
        if (pinned != null &&
            !MessageDigest.isEqual(fp.toByteArray(Charsets.UTF_8), pinned.lowercase().toByteArray(Charsets.UTF_8))
        ) {
            throw CertificateException(CERT_MISMATCH)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        const val CERT_MISMATCH = "certificate does not match the paired PC"
    }
}

data class PairedInfo(val id: String, val token: String, val name: String)

sealed interface Outcome {
    /** Handshake finished; [paired] is set when this was a pairing. */
    data class Ready(val paired: PairedInfo?, val seenFingerprint: String) : Outcome

    /** The PC answered with an error message (wrong code, unknown token, ...). */
    data class Rejected(val code: String, val message: String) : Outcome

    /** Network / TLS failure. [certMismatch]: the PC presented a different certificate than the pinned one. */
    data class Failed(val error: String, val certMismatch: Boolean = false) : Outcome
}

/**
 * One WebSocket connection to a PC agent. Created per attempt; never reused.
 * [open] performs the handshake (hello → pair/auth → ready); afterwards [sendBinary] is used for input
 * and [onState] / [onClosed] report what the PC tells us.
 */
class WsSession(private val base: OkHttpClient) {
    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var readyDone = false
    private val closedByUs = AtomicBoolean(false)
    private val closeNotified = AtomicBoolean(false)

    @Volatile
    var onState: ((percent: Int, muted: Boolean) -> Unit)? = null

    /** Called once when an established connection ends (not when we close it ourselves). */
    @Volatile
    var onClosed: ((reason: String) -> Unit)? = null

    suspend fun open(
        host: String,
        port: Int,
        pinned: String?,
        firstMessage: (nonce: String, seenFingerprint: String) -> String,
    ): Outcome {
        val url = try {
            HttpUrl.Builder().scheme("https").host(host).port(port).build()
        } catch (e: IllegalArgumentException) {
            return Outcome.Failed("invalid address: $host")
        }
        val trust = PinTrustManager(pinned)
        val client = try {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(trust), SecureRandom())
            base.newBuilder()
                .sslSocketFactory(ctx.socketFactory, trust)
                .hostnameVerifier { _, _ -> true } // identity is the pinned fingerprint, not a host name
                .build()
        } catch (e: Exception) {
            return Outcome.Failed("TLS setup failed: ${e.message}")
        }

        val result = CompletableDeferred<Outcome>()
        var paired: PairedInfo? = null

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try {
                    JSONObject(text)
                } catch (e: JSONException) {
                    return
                }
                when (j.optString("t")) {
                    "hello" -> {
                        if (j.optInt("v", -1) != Proto.VERSION) {
                            result.complete(Outcome.Rejected("version", "This PC runs an incompatible EasyRemote version."))
                            webSocket.close(1000, null)
                            return
                        }
                        val nonce = j.optString("nonce")
                        webSocket.send(firstMessage(nonce, trust.seen ?: ""))
                    }
                    "paired" -> paired = PairedInfo(j.optString("id"), j.optString("token"), j.optString("name"))
                    "ready" -> {
                        readyDone = true
                        result.complete(Outcome.Ready(paired, trust.seen ?: ""))
                    }
                    "state" -> onState?.invoke(j.optInt("vol"), j.optBoolean("mute"))
                    "error" -> {
                        val code = j.optString("code")
                        val msg = j.optString("msg")
                        if (!result.complete(Outcome.Rejected(code, msg))) {
                            finish("error: $msg")
                        }
                        webSocket.close(1000, null)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish("closed by PC")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val mismatch = generateSequence(t) { it.cause }
                    .any { it.message?.contains(PinTrustManager.CERT_MISMATCH) == true }
                finish(t.message ?: t.javaClass.simpleName, mismatch)
            }

            private fun finish(reason: String, mismatch: Boolean = false) {
                if (!result.complete(Outcome.Failed(reason, mismatch))) {
                    // handshake was already decided; this is the end of an established connection
                    if (readyDone && !closedByUs.get() && closeNotified.compareAndSet(false, true)) {
                        onClosed?.invoke(reason)
                    }
                }
            }
        }

        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        return try {
            withTimeout(12_000) { result.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            Outcome.Failed("timed out")
        } catch (e: kotlinx.coroutines.CancellationException) {
            close()
            throw e
        }
    }

    fun sendBinary(bytes: ByteArray): Boolean = ws?.send(bytes.toByteString()) ?: false

    fun close() {
        closedByUs.set(true)
        ws?.close(1000, null)
        ws?.cancel()
        ws = null
    }
}
