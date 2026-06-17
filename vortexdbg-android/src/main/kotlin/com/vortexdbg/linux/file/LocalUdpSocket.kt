package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.file.FileIO
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

import java.io.IOException
import java.net.InetSocketAddress

abstract class LocalUdpSocket protected constructor(@JvmField protected val emulator: Emulator<*>) : SocketIO(), FileIO {

    protected interface UdpHandler {
        @Throws(IOException::class)
        fun handle(request: ByteArray)
    }

    @JvmField
    protected var handler: UdpHandler? = null

    override fun close() {
        handler = null
    }

    override fun write(data: ByteArray): Int {
        try {
            handler!!.handle(data)
            return data.size
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    protected abstract fun connect(path: String): Int

    override fun connect(addr: Pointer, addrlen: Int): Int {
        val sa_family = addr.getShort(0)
        if (sa_family != AF_LOCAL) {
            throw UnsupportedOperationException("sa_family=$sa_family")
        }

        val path = addr.getString(2)
        log.debug("connect sa_family={}, path={}", sa_family, path)

        return connect(path)
    }

    override fun getTcpNoDelay(): Int {
        throw AbstractMethodError()
    }

    override fun setTcpNoDelay(tcpNoDelay: Int) {
        throw AbstractMethodError()
    }

    override fun setReuseAddress(reuseAddress: Int) {
        throw AbstractMethodError()
    }

    override fun setKeepAlive(keepAlive: Int) {
        throw AbstractMethodError()
    }

    override fun setSendBufferSize(size: Int) {
        throw AbstractMethodError()
    }

    override fun setReceiveBufferSize(size: Int) {
        throw AbstractMethodError()
    }

    override fun getLocalSocketAddress(): InetSocketAddress {
        throw AbstractMethodError()
    }

    override fun connect_ipv6(addr: Pointer, addrlen: Int): Int {
        throw AbstractMethodError()
    }

    override fun connect_ipv4(addr: Pointer, addrlen: Int): Int {
        throw AbstractMethodError()
    }

    companion object {
        private val log = LoggerFactory.getLogger(LocalUdpSocket::class.java)
    }
}
