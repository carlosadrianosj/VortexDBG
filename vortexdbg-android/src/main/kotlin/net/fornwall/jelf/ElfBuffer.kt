package net.fornwall.jelf

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class ElfBuffer(array: ByteArray) : ElfDataIn {

    private val buffer: ByteBuffer = ByteBuffer.wrap(array)

    init {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    @Throws(ElfException::class)
    override fun readUnsignedByte(): Short {
        return (buffer.get().toInt() and 0xff).toShort()
    }

    @Throws(ElfException::class)
    override fun readShort(): Short {
        return buffer.getShort()
    }

    @Throws(ElfException::class)
    override fun readInt(): Int {
        return buffer.getInt()
    }

    @Throws(ElfException::class)
    override fun readLong(): Long {
        return buffer.getLong()
    }

}
