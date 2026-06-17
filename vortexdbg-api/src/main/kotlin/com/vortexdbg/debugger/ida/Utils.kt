package com.vortexdbg.debugger.ida

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays

object Utils {

    @JvmStatic
    fun readCString(buffer: ByteBuffer): String {
        val baos = ByteArrayOutputStream()
        var read: Int
        while ((buffer.get().toInt() and 0xff).also { read = it } != 0) {
            baos.write(read)
        }
        return baos.toString()
    }

    @JvmStatic
    fun writeCString(buffer: ByteBuffer, str: String) {
        val data = str.toByteArray(StandardCharsets.UTF_8)
        buffer.put(Arrays.copyOf(data, data.size + 1))
    }

    @JvmStatic
    fun unpack_dd(buffer: ByteBuffer): Long {
        val b = buffer.get()
        if ((b.toInt() and 0xff) == 0xff) {
            return buffer.getInt().toLong() and 0xffffffffL
        } else if ((b.toInt() and 0xc0) == 0xc0) {
            val b0 = b.toInt() and 0x3f
            val b1 = buffer.get().toInt() and 0xff
            val b2 = buffer.get().toInt() and 0xff
            val b3 = buffer.get().toInt() and 0xff
            return ((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3).toLong() and 0xffffffffL
        } else if ((b.toInt() and 0x80) == 0x80) {
            val b0 = b.toInt() and 0x7f
            val b1 = buffer.get().toInt() and 0xff
            return ((b0 shl 8) or b1).toLong()
        } else {
            return (b.toInt() and 0xff).toLong()
        }
    }

    @JvmStatic
    fun unpack_dq(buffer: ByteBuffer): Long {
        val low = unpack_dd(buffer)
        val high = unpack_dd(buffer)
        return (high shl 32) or low
    }

    @JvmStatic
    fun pack_dq(value: Long): ByteArray {
        val d1 = pack_dd(value)
        val d2 = pack_dd(value shr 32)
        val data = ByteArray(d1.size + d2.size)
        System.arraycopy(d1, 0, data, 0, d1.size)
        System.arraycopy(d2, 0, data, d1.size, d2.size)
        return data
    }

    @JvmStatic
    fun pack_dd(value: Long): ByteArray {
        var value = value
        value = value and 0xffffffffL // unsigned int

        val buffer = ByteBuffer.allocate(0x10)
        if (value <= 0x7f) {
            buffer.put(value.toByte())
            return flipBuffer(buffer)
        }

        if ((value shr 14) == 0L) {
            buffer.put(((value shr 8) or 0x80).toByte())
            buffer.put(value.toByte())
            return flipBuffer(buffer)
        }

        if ((value shr 29) == 0L) {
            buffer.putInt((value or 0xc0000000L).toInt())
        } else {
            buffer.put(0xff.toByte())
            buffer.putInt(value.toInt())
        }
        return flipBuffer(buffer)
    }

    @JvmStatic
    fun flipBuffer(buffer: ByteBuffer): ByteArray {
        buffer.flip()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return data
    }

}
