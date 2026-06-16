package com.vortexdbg.unix.struct

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

import java.nio.charset.StandardCharsets

abstract class StdString internal constructor(p: Pointer?) : VortexdbgStructure(p) {

    fun getValue(emulator: Emulator<*>): String {
        return String(getData(emulator), StandardCharsets.UTF_8)
    }

    fun getData(emulator: Emulator<*>): ByteArray {
        return getDataPointer(emulator).getByteArray(0, getDataSize().toInt())
    }

    abstract fun getDataPointer(emulator: Emulator<*>): Pointer
    abstract fun getDataSize(): Long

    companion object {
        @JvmStatic
        fun createStdString(emulator: Emulator<*>, pointer: Pointer): StdString {
            return if (emulator.is64Bit()) {
                StdString64(pointer)
            } else {
                StdString32(pointer)
            }
        }
    }
}
