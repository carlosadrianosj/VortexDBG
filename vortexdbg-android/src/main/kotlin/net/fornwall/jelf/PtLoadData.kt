package net.fornwall.jelf

import com.sun.jna.Pointer

import java.nio.ByteBuffer

class PtLoadData internal constructor(private val buffer: ByteBuffer, private val dataSize: Long) {

    fun getDataSize(): Long {
        return dataSize
    }

    fun writeTo(ptr: Pointer) {
        var pointer = ptr
        val buf = ByteArray(Math.min(0x1000, buffer.remaining()))
        while (buffer.hasRemaining()) {
            val write = Math.min(buf.size, buffer.remaining())
            buffer.get(buf, 0, write)
            pointer.write(0, buf, 0, write)
            pointer = pointer.share(write.toLong())
        }
    }

}
