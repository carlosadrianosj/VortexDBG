package com.vortexdbg.linux.file

import com.vortexdbg.file.linux.StatStructure
import com.vortexdbg.utils.Inspector
import org.slf4j.LoggerFactory

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Arrays

internal class DnsProxyDaemon(private val sdk: Int) : LocalSocketIO.SocketHandler {

    private val baos = ByteArrayOutputStream(1024)

    override fun fstat(stat: StatStructure): Int {
        stat.st_size = 0L
        stat.st_blksize = 0
        stat.pack()
        return 0
    }

    @Throws(IOException::class)
    override fun handle(request: ByteArray): ByteArray? {
        baos.write(request)
        val data = baos.toByteArray()
        var endIndex = -1
        for (i in data.indices) {
            if (data[i].toInt() == 0) {
                endIndex = i
                break
            }
        }
        if (endIndex == -1) {
            return null
        }
        baos.reset()
        val command = String(data, 0, endIndex)
        if (command.startsWith("getaddrinfo")) {
            return getaddrinfo(command)
        } else if (command.startsWith("gethostbyaddr")) {
            return gethostbyaddr(command)
        }
        throw AbstractMethodError(command)
    }

    private fun gethostbyaddr(command: String): ByteArray {
        val buffer = ByteBuffer.allocate(1024)

        val tokens = command.split("\\s".toRegex()).toTypedArray()
        val addr = tokens[1]

        try {
            val address = InetAddress.getByName(addr)
            var host: String? = address.canonicalHostName
            if (host != null && host == addr) {
                host = null
            }

            if (host == null) {
                throw UnknownHostException()
            } else {
                buffer.put((DnsProxyQueryResult.toString() + "\u0000").toByteArray())
                val bytes = host.toByteArray(StandardCharsets.UTF_8)
                buffer.putInt(bytes.size + 1)
                buffer.put(bytes)
                buffer.put(0.toByte()) // NULL-terminated string

                buffer.putInt(0) // null to indicate we're done aliases

                buffer.putInt(SocketIO.AF_INET.toInt()) // addrtype
                buffer.putInt(4) // unknown length

                buffer.putInt(0) // null to indicate we're done addr_list
            }
        } catch (e: UnknownHostException) {
            buffer.put((DnsProxyOperationFailed.toString() + "\u0000").toByteArray())
            buffer.putInt(0)
        }

        buffer.flip()
        val response = ByteArray(buffer.remaining())
        buffer.get(response)
        if (log.isDebugEnabled) {
            Inspector.inspect(response, "gethostbyaddr")
        }
        return response
    }

    private fun getaddrinfo(command: String): ByteArray {
        val tokens = command.split("\\s".toRegex()).toTypedArray()
        val hostname = tokens[1]
        val servername = tokens[2]
        var port: Short = 0
        if ("^" != servername) {
            try {
                port = java.lang.Short.parseShort(servername)
            } catch (ignored: NumberFormatException) {
            }
        }
        val ai_flags = Integer.parseInt(tokens[3])
        val ai_family = Integer.parseInt(tokens[4])
        val ai_socktype = Integer.parseInt(tokens[5])
        val ai_protocol = Integer.parseInt(tokens[6])

        val buffer = ByteBuffer.allocate(1024)
        try {
            val addresses = InetAddress.getAllByName(hostname)
            if (log.isDebugEnabled) {
                log.debug("getaddrinfo hostname={}, servername={}, addresses={}, ai_flags={}, ai_family={}, ai_socktype={}, ai_protocol={}", hostname, servername, Arrays.toString(addresses), ai_flags, ai_family, ai_socktype, ai_protocol)
            }
            buffer.put((DnsProxyQueryResult.toString() + "\u0000").toByteArray())

            for (address in addresses) {
                putAddress(buffer, address, ai_flags, ai_socktype, port)
            }

            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(0) // NULL-terminated
        } catch (e: UnknownHostException) {
            val EAI_NODATA = 7
            buffer.put((DnsProxyOperationFailed.toString() + "\u0000").toByteArray())
            buffer.putInt(4)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(EAI_NODATA)
        }

        buffer.flip()
        val response = ByteArray(buffer.remaining())
        buffer.get(response)
        if (log.isDebugEnabled) {
            Inspector.inspect(response, "getaddrinfo")
        }
        return response
    }

    private fun putAddress(buffer: ByteBuffer, address: InetAddress, ai_flags: Int, ai_socktype: Int, port: Short) {
        if (sdk == 19) {
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(32) // sizeof(struct addrinfo)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(ai_flags)
            buffer.putInt(SocketIO.AF_INET.toInt())
            buffer.putInt(ai_socktype)
            buffer.putInt(SocketIO.IPPROTO_TCP)
            buffer.putInt(16) // ai_addrlen
            buffer.putInt(0) // ai_canonname
            buffer.putInt(0) // ai_addr
            buffer.putInt(0) // ai_next
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(16) // ai_addrlen
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(SocketIO.AF_INET) // sin_family
            buffer.putShort(java.lang.Short.reverseBytes(port)) // sin_port
            buffer.put(Arrays.copyOf(address.address, 4))
            buffer.put(ByteArray(8)) // __pad
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(0) // ai_canonname
        } else if (sdk == 23) {
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(1) // sizeof(struct addrinfo)
            buffer.putInt(ai_flags)
            buffer.putInt(SocketIO.AF_INET.toInt())
            buffer.putInt(ai_socktype)
            buffer.putInt(SocketIO.IPPROTO_TCP)
            buffer.putInt(16) // ai_addrlen
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(SocketIO.AF_INET) // sin_family
            buffer.putShort(java.lang.Short.reverseBytes(port)) // sin_port
            buffer.put(Arrays.copyOf(address.address, 4))
            buffer.put(ByteArray(8)) // __pad
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(0) // ai_canonname
        } else {
            throw IllegalStateException("sdk=$sdk")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DnsProxyDaemon::class.java)

        private const val DnsProxyQueryResult = 222
        private const val DnsProxyOperationFailed = 401
    }
}
