package com.vortexdbg.linux.file

import com.vortexdbg.file.linux.AndroidFileIO

import com.vortexdbg.arm.context.RegisterContext

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.FileIO
import com.vortexdbg.linux.struct.IFConf
import com.vortexdbg.linux.struct.IFReq
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.unix.UnixEmulator
import com.vortexdbg.unix.struct.SockAddr
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.BufferOverflowException
import java.util.Arrays
import java.util.Objects

open class UdpSocket(private val emulator: Emulator<*>) : SocketIO(), FileIO {

    private val datagramSocket: DatagramSocket

    init {
        try {
            this.datagramSocket = DatagramSocket()
        } catch (e: SocketException) {
            throw IllegalStateException(e)
        }
        if (emulator.getSyscallHandler().isVerbose()) {
            System.out.printf("Udp opened '%s' from %s%n", this, emulator.getContext<RegisterContext>().getLRPointer())
        }
    }

    override fun toString(): String {
        return datagramSocket.toString()
    }

    override fun close() {
        this.datagramSocket.close()
    }

    override fun connect_ipv6(addr: Pointer, addrlen: Int): Int {
        if (log.isDebugEnabled) {
            val data = addr.getByteArray(0, addrlen)
            Inspector.inspect(data, "addr")
        }

        val sa_family = addr.getShort(0).toInt()
        if (sa_family != AF_INET6.toInt()) {
            throw AbstractMethodError("sa_family=$sa_family")
        }

        try {
            val port = java.lang.Short.reverseBytes(addr.getShort(2)).toInt() and 0xffff
            val address = InetSocketAddress(InetAddress.getByAddress(addr.getByteArray(4, 16)), port)
            datagramSocket.connect(address)
            return 0
        } catch (e: IOException) {
            log.debug("connect ipv6 failed", e)
            emulator.getMemory().setErrno(UnixEmulator.ECONNREFUSED)
            return -1
        }
    }

    override fun connect_ipv4(addr: Pointer, addrlen: Int): Int {
        if (log.isDebugEnabled) {
            val data = addr.getByteArray(0, addrlen)
            Inspector.inspect(data, "addr")
        }

        val sa_family = addr.getShort(0).toInt()
        if (sa_family != AF_INET.toInt()) {
            throw AbstractMethodError("sa_family=$sa_family")
        }

        try {
            val port = java.lang.Short.reverseBytes(addr.getShort(2)).toInt() and 0xffff
            val address = InetSocketAddress(InetAddress.getByAddress(addr.getByteArray(8, 4)), port)
            datagramSocket.connect(address)
            return 0
        } catch (e: IOException) {
            log.debug("connect ipv4 failed", e)
            emulator.getMemory().setErrno(UnixEmulator.ECONNREFUSED)
            return -1
        }
    }

    override fun getLocalSocketAddress(): InetSocketAddress {
        return datagramSocket.getLocalSocketAddress() as InetSocketAddress
    }

    override fun write(data: ByteArray): Int {
        throw AbstractMethodError()
    }

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        throw AbstractMethodError()
    }

    override fun dup2(): FileIO {
        return UdpSocket(emulator)
    }

    override fun sendto(data: ByteArray, flags: Int, dest_addr: Pointer, addrlen: Int): Int {
        if (addrlen != 16) {
            throw IllegalStateException("addrlen=$addrlen")
        }

        if (log.isDebugEnabled) {
            val addr = dest_addr.getByteArray(0, addrlen)
            Inspector.inspect(addr, "addr")
        }

        val sa_family = dest_addr.getInt(0)
        if (sa_family != AF_INET.toInt()) {
            throw AbstractMethodError("sa_family=$sa_family")
        }

        try {
            val address = InetAddress.getByAddress(dest_addr.getByteArray(4, 4))
            throw UnsupportedOperationException("address=$address")
        } catch (e: IOException) {
            log.debug("sendto failed", e)
            emulator.getMemory().setErrno(UnixEmulator.EACCES)
            return -1
        }
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

    override fun setReuseAddress(reuseAddress: Int) {
        throw AbstractMethodError()
    }

    override fun setTcpNoDelay(tcpNoDelay: Int) {
        throw AbstractMethodError()
    }

    override fun getTcpNoDelay(): Int {
        throw AbstractMethodError()
    }

    override fun ioctl(emulator: Emulator<*>, request: Long, argp: Long): Int {
        if (request == AndroidFileIO.SIOCGIFCONF.toLong()) {
            return getIFaceList(emulator, argp)
        }
        if (request == AndroidFileIO.SIOCGIFFLAGS.toLong()) {
            return getIFaceFlags(emulator, argp)
        }
        if (request == AndroidFileIO.SIOCGIFNAME.toLong()) {
            return getIFaceName(emulator, argp)
        }

        return super.ioctl(emulator, request, argp)
    }

    private fun getIFaceList(emulator: Emulator<*>, argp: Long): Int {
        try {
            val list = getNetworkIFs(emulator)
            val conf = IFConf.create(emulator, VortexdbgPointer.pointer(emulator, argp))
            val ifcu_req = VortexdbgPointer.pointer(emulator, conf.getIfcuReq())
            var ifReq = IFReq.createIFReq(emulator, ifcu_req)
            if (list.size * ifReq.size() > conf.ifc_len) {
                throw BufferOverflowException()
            }

            conf.ifc_len = list.size * ifReq.size()
            conf.pack()

            var pointer: Pointer = Objects.requireNonNull(ifcu_req)
            for (networkIF in list) {
                ifReq = IFReq.createIFReq(emulator, pointer)
                ifReq.setName(networkIF.ifName)
                ifReq.pack()

                val sockAddr = SockAddr(ifReq.getAddrPointer())
                sockAddr.sin_family = AF_INET
                sockAddr.sin_port = 0.toShort()
                sockAddr.sin_addr = Arrays.copyOf(networkIF.ipv4.getAddress(), IPV4_ADDR_LEN - 4)
                sockAddr.pack()

                pointer = pointer.share(ifReq.size().toLong())
            }

            return 0
        } catch (e: SocketException) {
            throw IllegalStateException(e)
        }
    }

    protected open fun getIFaceFlags(emulator: Emulator<*>, argp: Long): Int {
        val req = IFReq.createIFReq(emulator, VortexdbgPointer.pointer(emulator, argp))
        req.unpack()
        val ifName = String(req.ifrn_name).trim()
        if (log.isDebugEnabled) {
            log.debug("get iface flags: {}", ifName)
        }
        var selected: NetworkIF? = null
        try {
            for (networkIF in getNetworkIFs(emulator)) {
                if (ifName == networkIF.ifName) {
                    selected = networkIF
                    break
                }
            }
        } catch (e: SocketException) {
            throw IllegalStateException(e)
        }
        if (selected == null) {
            throw UnsupportedOperationException("getIFaceFlags: $ifName")
        }
        val ptr = req.getAddrPointer()
        var flags = IFF_UP.toInt() or IFF_RUNNING.toInt()
        if (selected.isLoopback()) {
            flags = flags or IFF_LOOPBACK.toInt()
        } else if (selected.broadcast != null) {
            flags = flags or IFF_BROADCAST.toInt()
            flags = flags or IFF_MULTICAST.toInt()
        }
        ptr.setShort(0, flags.toShort())
        return 0
    }

    protected open fun getIFaceName(emulator: Emulator<*>, argp: Long): Int {
        val req = IFReq.createIFReq(emulator, VortexdbgPointer.pointer(emulator, argp))
        val ptr = req.getAddrPointer()
        val ifindex = ptr.getInt(0)
        if (log.isDebugEnabled) {
            log.debug("get iface name: {}", ifindex)
        }
        try {
            val list = getNetworkIFs(emulator)
            for (networkIF in list) {
                if (ifindex == networkIF.index) {
                    req.setName(networkIF.ifName)
                    req.pack()
                    return 0
                }
            }
            throw IllegalStateException("ifindex=$ifindex")
        } catch (e: SocketException) {
            throw IllegalStateException(e)
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(UdpSocket::class.java)
    }
}
