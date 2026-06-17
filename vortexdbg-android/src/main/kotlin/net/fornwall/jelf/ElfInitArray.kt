package net.fornwall.jelf

class ElfInitArray internal constructor(parser: ElfParser, offset: Long, size: Int) {

    @JvmField
    val array: LongArray

    init {
        parser.seek(offset)

        if (parser.elfFile.objectSize == ElfFile.CLASS_32) {
            array = LongArray(size / 4)
            for (i in array.indices) {
                array[i] = parser.readInt().toLong()
            }
        } else {
            array = LongArray(size / 8)
            for (i in array.indices) {
                array[i] = parser.readLong()
            }
        }
    }

}
