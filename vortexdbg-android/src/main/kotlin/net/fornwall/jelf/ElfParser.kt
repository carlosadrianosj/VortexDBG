package net.fornwall.jelf

import java.nio.ByteBuffer

/** Package internal class used for parsing ELF files. */
class ElfParser(@JvmField val elfFile: ElfFile, private val fsFile: ByteBuffer) : ElfDataIn {

    fun seek(offset: Long) {
        fsFile.position(offset.toInt())
    }

    /**
     * Signed byte utility functions used for converting from big-endian (MSB) to little-endian (LSB).
     */
    fun byteSwap(arg: Short): Short {
        return (((arg.toInt() shl 8) or ((arg.toInt() ushr 8) and 0xFF))).toShort()
    }

    fun byteSwap(arg: Int): Int {
        return (byteSwap(arg.toShort()).toInt() shl 16) or ((byteSwap((arg ushr 16).toShort()).toInt()) and 0xFFFF)
    }

    fun byteSwap(arg: Long): Long {
        return ((byteSwap(arg.toInt()).toLong() shl 32) or (byteSwap((arg ushr 32).toInt()).toLong()))
    }

    override fun readUnsignedByte(): Short {
        val `val` = fsFile.get().toInt() and 0xff
        return `val`.toShort()
    }

    @Throws(ElfException::class)
    override fun readShort(): Short {
        val ch1 = readUnsignedByte().toInt()
        val ch2 = readUnsignedByte().toInt()
        var `val` = ((ch1 shl 8) + (ch2)).toShort()
        if (elfFile.encoding == ElfFile.DATA_LSB) `val` = byteSwap(`val`)
        return `val`
    }

    @Throws(ElfException::class)
    override fun readInt(): Int {
        val ch1 = readUnsignedByte().toInt()
        val ch2 = readUnsignedByte().toInt()
        val ch3 = readUnsignedByte().toInt()
        val ch4 = readUnsignedByte().toInt()
        var `val` = ((ch1 shl 24) + (ch2 shl 16) + (ch3 shl 8) + (ch4))

        if (elfFile.encoding == ElfFile.DATA_LSB) {
            `val` = byteSwap(`val`)
        }
        return `val`
    }

    override fun readLong(): Long {
        val ch1 = readUnsignedByte().toInt()
        val ch2 = readUnsignedByte().toInt()
        val ch3 = readUnsignedByte().toInt()
        val ch4 = readUnsignedByte().toInt()
        val val1 = ((ch1 shl 24) + (ch2 shl 16) + (ch3 shl 8) + (ch4))
        val ch5 = readUnsignedByte().toInt()
        val ch6 = readUnsignedByte().toInt()
        val ch7 = readUnsignedByte().toInt()
        val ch8 = readUnsignedByte().toInt()
        val val2 = ((ch5 shl 24) + (ch6 shl 16) + (ch7 shl 8) + (ch8))

        var `val` = (val1.toLong() shl 32) + (val2.toLong() and 0xFFFFFFFFL)
        if (elfFile.encoding == ElfFile.DATA_LSB) {
            `val` = byteSwap(`val`)
        }
        return `val`
    }

    /** Read four-byte int or eight-byte long depending on if [ElfFile.objectSize]. */
    fun readIntOrLong(): Long {
        return if (elfFile.objectSize == ElfFile.CLASS_32) readInt().toLong() else readLong()
    }

    /** Returns a big-endian unsigned representation of the int. */
    fun unsignedByte(arg: Int): Long {
        val `val`: Long
        if (arg >= 0) {
            `val` = arg.toLong()
        } else {
            `val` = (unsignedByte((arg ushr 16).toShort().toInt()) shl 16) or (arg.toShort().toLong())
        }
        return `val`
    }

    fun read(data: ByteArray): Int {
        fsFile.get(data)
        return data.size
    }

    fun readBuffer(length: Int): ByteBuffer {
        val limit = fsFile.limit()
        try {
            fsFile.limit(fsFile.position() + length)
            return fsFile.slice()
        } finally {
            fsFile.limit(limit)
        }
    }

}
