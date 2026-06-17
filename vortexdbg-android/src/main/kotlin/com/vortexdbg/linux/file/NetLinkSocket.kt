package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.FileIO
import com.sun.jna.Pointer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Arrays

open class NetLinkSocket(private val emulator: Emulator<*>) : SocketIO(), FileIO {

    private var netlinkType: Short = 0
    private var netlinkFlags: Short = 0
    private var netlinkSeq = 0

    override fun write(data: ByteArray): Int {
        var buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val size = buffer.getInt()
        if (size - 4 > buffer.remaining()) {
            throw IllegalStateException("remaining=" + buffer.remaining() + ", size=" + size)
        }
        val tmp = ByteArray(size - 4)
        buffer.get(tmp)
        buffer = ByteBuffer.wrap(tmp)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        this.netlinkType = buffer.getShort()
        this.netlinkFlags = buffer.getShort()
        this.netlinkSeq = buffer.getInt()
        return size
    }

    override fun read(backend: Backend, buffer: Pointer, count: Int): Int {
        if (netlinkType.toInt() == -1) {
            return -1
        }

        return handleType(buffer, count, netlinkType)
    }

    protected open fun handleType(buffer: Pointer, count: Int, netlinkType: Short): Int {
        if (netlinkType == RTM_GETADDR && netlinkFlags.toInt() == (NLM_F_REQUEST.toInt() or NLM_F_MATCH.toInt())) {
            try {
                val list = getNetworkIFs(emulator)
                val baos = ByteArrayOutputStream()
                val bb = ByteBuffer.allocate(1024)
                bb.order(ByteOrder.LITTLE_ENDIAN)
                for (networkIF in list) {
                    bb.putInt(0) // length placeholder
                    bb.putShort(RTM_NEWADDR)
                    bb.putShort(NLM_F_MULTI)
                    bb.putInt(netlinkSeq)
                    bb.putInt(emulator.getPid())

                    bb.put(AF_INET.toByte()) // ifa_family
                    bb.put(0x8.toByte()) // ifa_prefixlen
                    bb.put(IFF_NOARP.toByte()) // ifa_flags
                    bb.put((-2).toByte()) // ifa_scope
                    bb.putInt(networkIF.index)

                    val IFA_ADDRESS: Short = 1
                    bb.putShort(0x8.toShort()) // rta_len
                    bb.putShort(IFA_ADDRESS)
                    bb.put(networkIF.ipv4.getAddress())

                    val IFA_LOCAL: Short = 2
                    bb.putShort(0x8.toShort()) // rta_len
                    bb.putShort(IFA_LOCAL)
                    bb.put(networkIF.ipv4.getAddress())

                    if (networkIF.broadcast != null) {
                        val IFA_BROADCAST: Short = 4
                        bb.putShort(0x8.toShort()) // rta_len
                        bb.putShort(IFA_BROADCAST)
                        bb.put(networkIF.broadcast!!.getAddress())
                    }

                    val IFA_LABEL: Short = 3
                    val label = networkIF.ifName.toByteArray(StandardCharsets.UTF_8)
                    val label_len = label.size + 5
                    bb.putShort(label_len.toShort()) // rta_len
                    bb.putShort(IFA_LABEL)
                    bb.put(Arrays.copyOf(label, label.size + 1))
                    val align = label_len % 4
                    var m = align
                    while (align > 0 && m < 4) {
                        bb.put(0x0.toByte())
                        m++
                    }

                    val __IFA_MAX: Short = 8
                    bb.putShort(0x8.toShort()) // rta_len
                    bb.putShort(__IFA_MAX)
                    bb.putInt(0x80)

                    val IFA_CACHEINFO: Short = 6
                    bb.putShort(0x14.toShort()) // rta_len
                    bb.putShort(IFA_CACHEINFO)
                    bb.putInt(-1) // ifa_prefered
                    bb.putInt(-1) // ifa_valid
                    bb.putInt(100) // cstamp
                    bb.putInt(200) // tstamp
                    bb.flip()

                    val nlmsg_len = bb.remaining()
                    bb.putInt(nlmsg_len)
                    baos.write(bb.array(), 0, nlmsg_len)
                    bb.clear()
                }
                val response = baos.toByteArray()
                if (count >= response.size) {
                    buffer.write(0, response, 0, response.size)
                    this.netlinkType = (-1).toShort()
                    return response.size
                }
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        }
        throw UnsupportedOperationException("buffer=" + buffer + ", count=" + count + ", netlinkType=0x" + Integer.toHexString(netlinkType.toInt()))
    }

    override fun getTcpNoDelay(): Int {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun setTcpNoDelay(tcpNoDelay: Int) {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun setReuseAddress(reuseAddress: Int) {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun setKeepAlive(keepAlive: Int) {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun setSendBufferSize(size: Int) {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun setReceiveBufferSize(size: Int) {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun getLocalSocketAddress(): InetSocketAddress {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun connect_ipv6(addr: Pointer, addrlen: Int): Int {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun connect_ipv4(addr: Pointer, addrlen: Int): Int {
        throw UnsupportedOperationException(javaClass.name)
    }

    override fun close() {
        netlinkType = 0
        netlinkFlags = 0
    }

    companion object {
        private const val RTM_NEWADDR: Short = 0x14
        private const val RTM_GETADDR: Short = 0x16

        private const val NLM_F_REQUEST: Short = 0x1
        private const val NLM_F_MULTI: Short = 0x2
        private const val NLM_F_MATCH: Short = 0x200
    }
}
