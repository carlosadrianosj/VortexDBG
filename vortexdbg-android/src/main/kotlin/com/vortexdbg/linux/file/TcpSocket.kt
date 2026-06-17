package com.vortexdbg.linux.file

import com.vortexdbg.arm.context.RegisterContext

import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.unix.UnixEmulator
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.apache.commons.codec.binary.Hex
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Arrays

open class TcpSocket private constructor(private val emulator: Emulator<*>, private val socket: Socket) : SocketIO(), FileIO {

    private var serverSocket: ServerSocket? = null

    constructor(emulator: Emulator<*>) : this(emulator, Socket())

    init {
        if (emulator.getSyscallHandler().isVerbose()) {
            System.out.printf("Tcp opened '%s' from %s%n", this, emulator.getContext<RegisterContext>().getLRPointer())
        }
    }

    @JvmField
    protected var outputStream: OutputStream? = null
    @JvmField
    protected var inputStream: InputStream? = null

    override fun close() {
        IOUtils.close(outputStream)
        IOUtils.close(inputStream)
        IOUtils.close(socket)
        IOUtils.close(serverSocket)
    }

    override fun write(data: ByteArray): Int {
        try {
            if (log.isDebugEnabled) {
                Inspector.inspect(data, "write hex=" + Hex.encodeHexString(data))
            }
            outputStream!!.write(data)
            return data.size
        } catch (e: IOException) {
            log.debug("write failed", e)
            return -1
        }
    }

    override fun recvfrom(backend: Backend, buf: Pointer, len: Int, flags: Int, src_addr: Pointer, addrlen: Pointer): Int {
        val peek = (flags and MSG_PEEK) != 0
        if (peek &&
            (flags and MSG_PEEK.inv()) == 0 &&
            inputStream!!.markSupported() &&
            src_addr == null && addrlen == null) {
            try {
                inputStream!!.mark(len)
                return readInternal(buf, len, false)
            } finally {
                try {
                    inputStream!!.reset()
                } catch (e: IOException) {
                    log.warn("recvfrom", e)
                }
            }
        }

        return super.recvfrom(backend, buf, len, flags, src_addr, addrlen)
    }

    private var receiveBuf: ByteArray? = null

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        return readInternal(buffer, count, true)
    }

    protected open fun readInternal(buffer: Pointer, count: Int, logRead: Boolean): Int {
        try {
            if (receiveBuf == null) {
                receiveBuf = ByteArray(socket.getReceiveBufferSize())
            }
            val read = inputStream!!.read(receiveBuf, 0, Math.min(count, receiveBuf!!.size))
            if (read <= 0) {
                return read
            }

            val data = Arrays.copyOf(receiveBuf, read)
            buffer.write(0L, data, 0, data.size)
            if (logRead && log.isDebugEnabled) {
                Inspector.inspect(data, "readInternal socket=$socket")
            }
            return data.size
        } catch (e: IOException) {
            log.debug("readInternal", e)
            return -1
        }
    }

    override fun listen(backlog: Int): Int {
        try {
            serverSocket = ServerSocket()
            IOUtils.close(socket)
            serverSocket!!.bind(socket.getLocalSocketAddress(), backlog)
            return 0
        } catch (e: IOException) {
            log.debug("listen failed", e)
            emulator.getMemory().setErrno(UnixEmulator.EOPNOTSUPP)
            return -1
        }
    }

    override fun accept(addr: Pointer, addrlen: Pointer): AndroidFileIO {
        try {
            val socket = serverSocket!!.accept()
            val io = TcpSocket(emulator, socket)
            io.inputStream = BufferedInputStream(socket.getInputStream())
            io.outputStream = socket.getOutputStream()
            if (addr != null) {
                io.getpeername(addr, addrlen)
            }
            return io
        } catch (e: IOException) {
            log.debug("accept failed", e)
            emulator.getMemory().setErrno(UnixEmulator.EAGAIN)
            return null as AndroidFileIO
        }
    }

    override fun bind_ipv4(addr: Pointer, addrlen: Int): Int {
        val sa_family = addr.getShort(0).toInt()
        if (sa_family != AF_INET.toInt()) {
            throw AbstractMethodError("sa_family=$sa_family")
        }

        try {
            val port = java.lang.Short.reverseBytes(addr.getShort(2)).toInt() and 0xffff
            val address = InetSocketAddress(InetAddress.getByAddress(addr.getByteArray(4, 4)), port)
            if (log.isDebugEnabled) {
                val data = addr.getByteArray(0L, addrlen)
                Inspector.inspect(data, "address=$address")
            }
            socket.bind(address)
            return 0
        } catch (e: IOException) {
            log.debug("bind ipv4 failed", e)
            emulator.getMemory().setErrno(UnixEmulator.EADDRINUSE)
            return -1
        }
    }

    override fun connect_ipv4(addr: Pointer, addrlen: Int): Int {
        if (log.isDebugEnabled) {
            val data = addr.getByteArray(0L, addrlen)
            Inspector.inspect(data, "addr")
        }

        val sa_family = addr.getShort(0).toInt()
        if (sa_family != AF_INET.toInt()) {
            throw AbstractMethodError("sa_family=$sa_family")
        }

        try {
            val port = java.lang.Short.reverseBytes(addr.getShort(2)).toInt() and 0xffff
            val address = InetSocketAddress(InetAddress.getByAddress(addr.getByteArray(4, 4)), port)
            socket.connect(address)
            outputStream = socket.getOutputStream()
            inputStream = BufferedInputStream(socket.getInputStream())
            return 0
        } catch (e: IOException) {
            log.debug("connect ipv4 failed", e)
            emulator.getMemory().setErrno(UnixEmulator.ECONNREFUSED)
            return -1
        }
    }

    override fun connect_ipv6(addr: Pointer, addrlen: Int): Int {
        if (log.isDebugEnabled) {
            val data = addr.getByteArray(0L, addrlen)
            Inspector.inspect(data, "addr")
        }

        val sa_family = addr.getShort(0).toInt()
        if (sa_family != AF_INET6.toInt()) {
            throw AbstractMethodError("sa_family=$sa_family")
        }

        try {
            val port = java.lang.Short.reverseBytes(addr.getShort(2)).toInt() and 0xffff
            val address = InetSocketAddress(InetAddress.getByAddress(addr.getByteArray(8, 16)), port)
            socket.connect(address)
            outputStream = socket.getOutputStream()
            inputStream = BufferedInputStream(socket.getInputStream())
            return 0
        } catch (e: IOException) {
            log.debug("connect ipv6 failed", e)
            emulator.getMemory().setErrno(UnixEmulator.ECONNREFUSED)
            return -1
        }
    }

    override fun getpeername(addr: Pointer, addrlen: Pointer): Int {
        val remote = socket.getRemoteSocketAddress() as InetSocketAddress
        fillAddress(remote, addr, addrlen)
        return 0
    }

    override fun getLocalSocketAddress(): InetSocketAddress {
        return socket.getLocalSocketAddress() as InetSocketAddress
    }

    @Throws(SocketException::class)
    override fun setKeepAlive(keepAlive: Int) {
        socket.setKeepAlive(keepAlive != 0)
    }

    @Throws(SocketException::class)
    override fun setSendBufferSize(size: Int) {
        socket.setSendBufferSize(size)
    }

    @Throws(SocketException::class)
    override fun setReceiveBufferSize(size: Int) {
        socket.setReceiveBufferSize(size)
    }

    @Throws(SocketException::class)
    override fun setReuseAddress(reuseAddress: Int) {
        socket.setReuseAddress(reuseAddress != 0)
    }

    @Throws(SocketException::class)
    override fun setTcpNoDelay(tcpNoDelay: Int) {
        socket.setTcpNoDelay(tcpNoDelay != 0)
    }

    @Throws(SocketException::class)
    override fun getTcpNoDelay(): Int {
        return if (socket.getTcpNoDelay()) 1 else 0
    }

    override fun shutdown(how: Int): Int {
        when (how) {
            SHUT_RD, SHUT_WR -> {
                IOUtils.close(outputStream)
                outputStream = null
                return 0
            }
            SHUT_RDWR -> {
                IOUtils.close(outputStream)
                IOUtils.close(inputStream)
                outputStream = null
                inputStream = null
                return 0
            }
        }

        return super.shutdown(how)
    }

    override fun toString(): String {
        return socket.toString()
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(TcpSocket::class.java)
    }
}
