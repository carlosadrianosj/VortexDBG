package com.vortexdbg.unix.struct

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

/**
 * Note: Only compatible with libc++, though libstdc++'s std::string is a lot simpler.
 */
class StdString32 internal constructor(p: Pointer?) : StdString(p) {

    init {
        unpack()
    }

    @JvmField
    var isTiny: Byte = 0
    @JvmField
    var size: Int = 0
    @JvmField
    var value: Int = 0

    override fun getFieldOrder(): List<String> {
        return listOf("isTiny", "size", "value")
    }

    override fun getDataPointer(emulator: Emulator<*>): Pointer {
        val isTiny = (this.isTiny.toInt() and 1) == 0
        return if (isTiny) {
            getPointer().share(1)
        } else {
            VortexdbgPointer.pointer(emulator, value.toLong())
        }
    }

    override fun getDataSize(): Long {
        val isTiny = (this.isTiny.toInt() and 1) == 0
        return if (isTiny) {
            ((this.isTiny.toInt() and 0xff) shr 1).toLong()
        } else {
            size.toLong()
        }
    }
}
