package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.linux.StatStructure
import com.vortexdbg.unix.UnixEmulator
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Arrays

open class LocalSocketIO(private val emulator: Emulator<*>, private val sdk: Int) : SocketIO(), FileIO {

    interface SocketHandler {
        @Throws(IOException::class)
        fun handle(request: ByteArray): ByteArray?
        fun fstat(stat: StatStructure): Int
    }

    override fun close() {
        response = null
        handler = null
    }

    private var response: ByteArray? = null

    override fun write(data: ByteArray): Int {
        try {
            val response = handler!!.handle(data)
            if (response != null) {
                this.response = response
            }
            return data.size
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        val response = this.response ?: throw IllegalStateException("response is null")
        if (response.size <= count) {
            buffer.write(0L, response, 0, response.size)
            val ret = response.size
            this.response = null
            return ret
        } else {
            buffer.write(0L, Arrays.copyOf(response, count), 0, count)
            val temp = ByteArray(response.size - count)
            System.arraycopy(response, count, temp, 0, temp.size)
            this.response = temp
            return count
        }
    }

    override fun fstat(emulator: Emulator<*>, stat: StatStructure): Int {
        return handler!!.fstat(stat)
    }

    override fun getLocalSocketAddress(): InetSocketAddress {
        throw AbstractMethodError()
    }

    private var handler: SocketHandler? = null

    protected open fun resolveHandler(path: String): SocketHandler? {
        if ("/dev/socket/dnsproxyd" == path) {
            return DnsProxyDaemon(sdk)
        }
        return null
    }

    final override fun connect(addr: Pointer, addrlen: Int): Int {
        val sa_family = addr.getShort(0)
        if (sa_family.toInt() != AF_LOCAL) {
            throw UnsupportedOperationException("sa_family=$sa_family")
        }
        val path = String(addr.getByteArray(2, addrlen - 2), StandardCharsets.UTF_8).trim()
        if (log.isDebugEnabled) {
            log.debug("connect sa_family={}, path={}", sa_family, path)
        }

        handler = resolveHandler(path)
        return if (handler != null) {
            0
        } else {
            emulator.getMemory().setErrno(UnixEmulator.EPERM)
            -1
        }
    }

    override fun bind(addr: Pointer, addrlen: Int): Int {
        if (log.isDebugEnabled) {
            log.debug(Inspector.inspectString(addr.getByteArray(0L, addrlen), "bind addrlen=$addrlen"))
        }
        emulator.getMemory().setErrno(UnixEmulator.EPERM)
        return -1
    }

    override fun connect_ipv6(addr: Pointer, addrlen: Int): Int {
        throw AbstractMethodError()
    }

    override fun connect_ipv4(addr: Pointer, addrlen: Int): Int {
        throw AbstractMethodError()
    }

    override fun setReuseAddress(reuseAddress: Int) {
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

    override fun setTcpNoDelay(tcpNoDelay: Int) {
        throw AbstractMethodError()
    }

    override fun getTcpNoDelay(): Int {
        throw AbstractMethodError()
    }

    companion object {
        private val log = LoggerFactory.getLogger(LocalSocketIO::class.java)
    }
}
