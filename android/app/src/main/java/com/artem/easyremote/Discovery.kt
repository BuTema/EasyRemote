package com.artem.easyremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address

data class Found(val host: String, val port: Int, val name: String)

/**
 * Finds PCs on the local network with mDNS (`_easyremote._tcp`). Runs only while the app is in the
 * foreground (started / stopped by the activity), so it costs nothing in the background.
 * What is learned here is only an address; trust always comes from the pinned certificate.
 */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()

    private val _found = MutableStateFlow<Map<String, Found>>(emptyMap())

    /** Keyed by the PC's short id (first 16 hex chars of its certificate fingerprint). */
    val found: StateFlow<Map<String, Found>> = _found

    private var listener: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        synchronized(lock) {
            if (listener != null) return
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    synchronized(lock) { listener = null }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    synchronized(lock) { queue.addLast(serviceInfo) }
                    pump()
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    _found.value = _found.value.filterValues { it.name != serviceInfo.serviceName }
                }
            }
            listener = l
            try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            } catch (e: Exception) {
                listener = null
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            listener?.let {
                try {
                    nsd.stopServiceDiscovery(it)
                } catch (e: Exception) {
                    // already stopped
                }
            }
            listener = null
            queue.clear()
            resolving = false
        }
        _found.value = emptyMap()
    }

    /** NsdManager can resolve only one service at a time, so resolutions are queued. */
    private fun pump() {
        val next: NsdServiceInfo = synchronized(lock) {
            if (resolving || queue.isEmpty()) return
            resolving = true
            queue.removeFirst()
        }
        val done = {
            synchronized(lock) { resolving = false }
            pump()
        }
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    done()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handleResolved(serviceInfo)
                    done()
                }
            })
        } catch (e: Exception) {
            done()
        }
    }

    private fun handleResolved(info: NsdServiceInfo) {
        val id = info.attributes["id"]?.toString(Charsets.UTF_8) ?: return
        @Suppress("DEPRECATION")
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val ip = addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: return
        _found.value = _found.value + (id to Found(ip, info.port, info.serviceName))
    }

    companion object {
        const val SERVICE_TYPE = "_easyremote._tcp."
    }
}
