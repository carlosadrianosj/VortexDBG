package com.vortexdbg.linux.file

import com.vortexdbg.arm.context.RegisterContext

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.BaseAndroidFileIO
import com.vortexdbg.file.linux.IOConstants
import com.vortexdbg.file.linux.StatStructure
import com.vortexdbg.unix.IO
import com.vortexdbg.unix.struct.SockAddr
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.ArrayList
import java.util.Arrays

abstract class SocketIO protected constructor() : BaseAndroidFileIO(IOConstants.O_RDWR), AndroidFileIO {

    @Throws(SocketException::class)
    protected open fun getNetworkIFs(emulator: Emulator<*>): List<NetworkIF> {
        val enumeration = NetworkInterface.getNetworkInterfaces()
        val list: MutableList<NetworkIF> = ArrayList()
        while (enumeration.hasMoreElements()) {
            val networkInterface = enumeration.nextElement()
            val addressEnumeration = networkInterface.inetAddresses
            while (addressEnumeration.hasMoreElements()) {
                val address = addressEnumeration.nextElement()
                if (address is Inet4Address) {
                    var broadcast: Inet4Address? = null
                    for (interfaceAddress in networkInterface.interfaceAddresses) {
                        if (interfaceAddress.broadcast != null) {
                            broadcast = interfaceAddress.broadcast as Inet4Address
                            break
                        }
                    }
                    list.add(NetworkIF(networkInterface.index, networkInterface.name, address, broadcast))
                    break
                }
            }
        }
        if (log.isDebugEnabled) {
            log.debug("Return host network ifs: {}", list)
        }
        if (emulator.getSyscallHandler().isVerbose()) {
            println(javaClass.simpleName + " return host network ifs: " + list + " from " + emulator.getContext<RegisterContext>().getLRPointer())
        }
        return list
    }

    override fun getsockopt(level: Int, optname: Int, optval: Pointer, optlen: Pointer): Int {
        try {
            when (level) {
                SOL_SOCKET -> {
                    if (optname == SO_ERROR) {
                        optlen.setInt(0L, 4)
                        optval.setInt(0L, 0)
                        return 0
                    }
                }
                IPPROTO_TCP -> {
                    if (optname == TCP_NODELAY) {
                        optlen.setInt(0L, 4)
                        optval.setInt(0L, getTcpNoDelay())
                        return 0
                    }
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
        return super.getsockopt(level, optname, optval, optlen)
    }

    @Throws(SocketException::class)
    protected abstract fun getTcpNoDelay(): Int

    override fun setsockopt(level: Int, optname: Int, optval: Pointer, optlen: Int): Int {
        try {
            when (level) {
                SOL_SOCKET -> {
                    when (optname) {
                        SO_REUSEADDR -> {
                            if (optlen != 4) {
                                throw IllegalStateException("optlen=$optlen")
                            }
                            setReuseAddress(optval.getInt(0L))
                            return 0
                        }
                        SO_BROADCAST -> {
                            if (optlen != 4) {
                                throw IllegalStateException("optlen=$optlen")
                            }
                            optval.getInt(0L) // broadcast_pings
                            return 0
                        }
                        SO_SNDBUF -> {
                            if (optlen != 4) {
                                throw IllegalStateException("optlen=$optlen")
                            }
                            setSendBufferSize(optval.getInt(0L))
                            return 0
                        }
                        SO_RCVBUF -> {
                            if (optlen != 4) {
                                throw IllegalStateException("optlen=$optlen")
                            }
                            setReceiveBufferSize(optval.getInt(0L))
                            return 0
                        }
                        SO_KEEPALIVE -> {
                            if (optlen != 4) {
                                throw IllegalStateException("optlen=$optlen")
                            }
                            setKeepAlive(optval.getInt(0L))
                            return 0
                        }
                        SO_RCVTIMEO, SO_SNDTIMEO -> {
                            return 0
                        }
                    }
                }
                IPPROTO_TCP -> {
                    when (optname) {
                        TCP_NODELAY -> {
                            if (optlen != 4) {
                                throw IllegalStateException("optlen=$optlen")
                            }
                            setTcpNoDelay(optval.getInt(0L))
                            return 0
                        }
                        TCP_MAXSEG -> {
                            if (optlen != 4) {
                                throw IllegalStateException("optlen=$optlen")
                            }
                            log.debug("setsockopt TCP_MAXSEG={}", optval.getInt(0L))
                            return 0
                        }
                    }
                }
                IPPROTO_IP -> {
                    return 0
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }

        log.warn("setsockopt level={}, optname={}, optval={}, optlen={}", level, optname, optval, optlen)
        return 0
    }

    @Throws(SocketException::class)
    protected abstract fun setTcpNoDelay(tcpNoDelay: Int)

    @Throws(SocketException::class)
    protected abstract fun setReuseAddress(reuseAddress: Int)

    @Throws(SocketException::class)
    protected abstract fun setKeepAlive(keepAlive: Int)

    @Throws(SocketException::class)
    protected abstract fun setSendBufferSize(size: Int)

    @Throws(SocketException::class)
    protected abstract fun setReceiveBufferSize(size: Int)

    override fun getsockname(addr: Pointer, addrlen: Pointer): Int {
        val local = getLocalSocketAddress()
        fillAddress(local, addr, addrlen)
        return 0
    }

    protected fun fillAddress(socketAddress: InetSocketAddress, addr: Pointer, addrlen: Pointer) {
        val address = socketAddress.address
        val sockAddr = SockAddr(addr)
        sockAddr.sin_port = socketAddress.port.toShort()
        if (address is Inet4Address) {
            sockAddr.sin_family = AF_INET.toShort()
            sockAddr.sin_addr = Arrays.copyOf(address.getAddress(), IPV4_ADDR_LEN - 4)
            addrlen.setInt(0L, IPV4_ADDR_LEN)
        } else if (address is Inet6Address) {
            sockAddr.sin_family = AF_INET6.toShort()
            sockAddr.sin_addr = Arrays.copyOf(address.getAddress(), IPV6_ADDR_LEN - 4)
            addrlen.setInt(0L, IPV6_ADDR_LEN)
        } else {
            throw UnsupportedOperationException()
        }
    }

    protected abstract fun getLocalSocketAddress(): InetSocketAddress

    override fun connect(addr: Pointer, addrlen: Int): Int {
        return if (addrlen == IPV4_ADDR_LEN) {
            connect_ipv4(addr, addrlen)
        } else if (addrlen == IPV6_ADDR_LEN) {
            connect_ipv6(addr, addrlen)
        } else {
            throw IllegalStateException("addrlen=$addrlen")
        }
    }

    override fun bind(addr: Pointer, addrlen: Int): Int {
        return if (addrlen == IPV4_ADDR_LEN) {
            bind_ipv4(addr, addrlen)
        } else if (addrlen == IPV6_ADDR_LEN) {
            bind_ipv6(addr, addrlen)
        } else {
            throw IllegalStateException("addrlen=$addrlen")
        }
    }

    protected abstract fun connect_ipv6(addr: Pointer, addrlen: Int): Int

    protected abstract fun connect_ipv4(addr: Pointer, addrlen: Int): Int

    protected open fun bind_ipv6(addr: Pointer, addrlen: Int): Int {
        throw AbstractMethodError(javaClass.name)
    }

    protected open fun bind_ipv4(addr: Pointer, addrlen: Int): Int {
        throw AbstractMethodError(javaClass.name)
    }

    override fun recvfrom(backend: Backend, buf: Pointer, len: Int, flags: Int, src_addr: Pointer, addrlen: Pointer): Int {
        if (flags == 0x0 && src_addr == null && addrlen == null) {
            return read(backend, buf, len)
        }

        return super.recvfrom(backend, buf, len, flags, src_addr, addrlen)
    }

    override fun sendto(data: ByteArray, flags: Int, dest_addr: Pointer, addrlen: Int): Int {
        val newFlags = flags and MSG_NOSIGNAL.inv()

        if (newFlags == 0x0 && dest_addr == null && addrlen == 0) {
            return write(data)
        }

        return super.sendto(data, newFlags, dest_addr, addrlen)
    }

    override fun fstat(emulator: Emulator<*>, stat: StatStructure): Int {
        stat.st_dev = 0
        stat.st_mode = IO.S_IFSOCK
        stat.st_uid = 0
        stat.st_gid = 0
        stat.st_size = 0
        stat.st_blksize = 0
        stat.st_ino = 0
        stat.pack()
        return 0
    }

    override fun getdents64(dirp: Pointer, size: Int): Int {
        throw UnsupportedOperationException()
    }

    companion object {
        private val log = LoggerFactory.getLogger(SocketIO::class.java)

        const val AF_UNSPEC = 0
        const val AF_LOCAL = 1 // AF_UNIX
        const val AF_INET = 2
        const val AF_INET6 = 10
        const val AF_NETLINK = 16
        const val AF_ROUTE = 17 /* Internal Routing Protocol */
        const val AF_LINK = 18 /* Link layer interface */

        const val IPV4_ADDR_LEN = 16
        const val IPV6_ADDR_LEN = 28

        const val SOCK_STREAM = 1
        const val SOCK_DGRAM = 2
        const val SOCK_RAW = 3
        const val SOCK_SEQPACKET = 5

        private const val IPPROTO_IP = 0
        const val IPPROTO_ICMP = 1
        const val IPPROTO_TCP = 6

        const val SOL_SOCKET = 1

        private const val SO_REUSEADDR = 2
        private const val SO_ERROR = 4
        private const val SO_BROADCAST = 6
        private const val SO_SNDBUF = 7
        private const val SO_RCVBUF = 8
        private const val SO_KEEPALIVE = 9
        private const val SO_RCVTIMEO = 20
        private const val SO_SNDTIMEO = 21
        const val SO_PEERSEC = 31

        const val SHUT_RD = 0
        const val SHUT_WR = 1
        const val SHUT_RDWR = 2

        private const val TCP_NODELAY = 1
        private const val TCP_MAXSEG = 2

        const val MSG_PEEK = 0x02 /* Peek at incoming messages. */
        const val MSG_NOSIGNAL = 0x4000 /* Do not generate SIGPIPE. */

        @JvmField
        var IFF_UP: Short = 0x1 /* interface is up		*/
        @JvmField
        var IFF_BROADCAST: Short = 0x2 /* broadcast address valid	*/
        @JvmField
        var IFF_LOOPBACK: Short = 0x8 /* is a loopback net		*/
        @JvmField
        var IFF_RUNNING: Short = 0x40 /* interface RFC2863 OPER_UP	*/
        @JvmField
        var IFF_NOARP: Short = 0x80 /* no ARP protocol		*/
        @JvmField
        var IFF_MULTICAST: Short = 0x1000 /* Supports multicast		*/
    }
}
