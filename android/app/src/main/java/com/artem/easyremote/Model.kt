package com.artem.easyremote

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A paired PC. [fingerprint] (SHA-256 of its TLS certificate, lowercase hex) is its identity. */
data class Pc(
    val fingerprint: String,
    val name: String,
    /** Last address that worked; refreshed from mDNS when the PC is seen on the network. */
    val host: String,
    val port: Int,
    /** Id this phone received from the PC when pairing. */
    val deviceId: String,
    val token: String,
) {
    /** The `id` advertised in the mDNS TXT record. */
    val shortId: String get() = fingerprint.take(16)
}

data class VolumeInfo(val percent: Int, val muted: Boolean)

class PcStore(context: Context) {
    private val prefs = context.getSharedPreferences("pcs", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Pc> {
        val text = prefs.getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Pc(
                    fingerprint = o.getString("fp"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.getInt("port"),
                    deviceId = o.getString("did"),
                    token = o.getString("token"),
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    @Synchronized
    private fun write(list: List<Pc>) {
        val arr = JSONArray()
        for (pc in list) {
            arr.put(
                JSONObject()
                    .put("fp", pc.fingerprint)
                    .put("name", pc.name)
                    .put("host", pc.host)
                    .put("port", pc.port)
                    .put("did", pc.deviceId)
                    .put("token", pc.token)
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    @Synchronized
    fun save(pc: Pc) = write(all().filter { it.fingerprint != pc.fingerprint } + pc)

    @Synchronized
    fun remove(fingerprint: String) = write(all().filter { it.fingerprint != fingerprint })

    @Synchronized
    fun updateAddress(fingerprint: String, host: String, port: Int) =
        write(all().map { if (it.fingerprint == fingerprint) it.copy(host = host, port = port) else it })
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Two fingers moving up scroll the page down, like on a phone. */
    val naturalScroll = MutableStateFlow(prefs.getBoolean("natural", true))
    val pointerSpeed = MutableStateFlow(prefs.getFloat("speed", 1.0f))
    val volumeKeys = MutableStateFlow(prefs.getBoolean("volkeys", true))
    val keepScreenOn = MutableStateFlow(prefs.getBoolean("awake", false))

    fun setNaturalScroll(v: Boolean) {
        naturalScroll.value = v
        prefs.edit().putBoolean("natural", v).apply()
    }

    fun setPointerSpeed(v: Float) {
        pointerSpeed.value = v
        prefs.edit().putFloat("speed", v).apply()
    }

    fun setVolumeKeys(v: Boolean) {
        volumeKeys.value = v
        prefs.edit().putBoolean("volkeys", v).apply()
    }

    fun setKeepScreenOn(v: Boolean) {
        keepScreenOn.value = v
        prefs.edit().putBoolean("awake", v).apply()
    }
}

/** Content of the pairing QR code: `easyremote://pair?h=<host>&p=<port>&fp=<sha256>&s=<code>&n=<name>` */
data class PairLink(val host: String, val port: Int, val fingerprint: String, val secret: String, val name: String)

fun parsePairLink(text: String): PairLink? {
    val uri = try {
        Uri.parse(text.trim())
    } catch (e: Exception) {
        return null
    }
    if (uri.scheme != "easyremote" || uri.host != "pair") return null
    val host = uri.getQueryParameter("h") ?: return null
    val port = uri.getQueryParameter("p")?.toIntOrNull() ?: return null
    val fp = uri.getQueryParameter("fp")?.lowercase() ?: return null
    val secret = uri.getQueryParameter("s") ?: return null
    if (host.isBlank() || port !in 1..65535 || secret.isBlank()) return null
    if (fp.length != 64 || !fp.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return PairLink(host, port, fp, secret, uri.getQueryParameter("n") ?: host)
}
