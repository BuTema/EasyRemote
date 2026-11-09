package com.artem.easyremote

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Sockets with TCP_NODELAY. Without it Android's TCP stack holds back small packets (Nagle's
 * algorithm) until the previous one is acknowledged, and combined with the PC's delayed ACKs that
 * turns a steady stream of tiny pointer-movement frames into bursts, which is felt as cursor jumps.
 */
class NoDelaySocketFactory(private val delegate: SocketFactory = getDefault()) : SocketFactory() {
    private fun tune(s: Socket): Socket = s.apply { tcpNoDelay = true }

    override fun createSocket(): Socket = tune(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket = tune(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        tune(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = tune(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        tune(delegate.createSocket(address, port, localAddress, localPort))
}
