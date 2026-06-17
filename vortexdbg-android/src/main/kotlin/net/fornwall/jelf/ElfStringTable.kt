package net.fornwall.jelf


import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ElfStringTable
/** Reads all the strings from [offset, length]. */
@Throws(ElfException::class)
constructor(parser: ElfParser, offset: Long, length: Int) {

    /** The string table data. */
    private val buffer: ByteBuffer

    init {
        parser.seek(offset)
        buffer = parser.readBuffer(length)
    }

    private val baos = ByteArrayOutputStream(16)

    fun get(index: Int): String {
        buffer.position(index)
        baos.reset()
        var b: Byte
        while ((buffer.get().also { b = it }).toInt() != 0) {
            baos.write(b.toInt())
        }
        return baos.toString()
    }
}
