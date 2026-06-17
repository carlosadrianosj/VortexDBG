package com.vortexdbg.linux.thread

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

abstract class PThreadInternal(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var tid: Int = 0

    companion object {
        @JvmStatic
        fun create(emulator: Emulator<*>, pointer: Pointer): PThreadInternal {
            return if (emulator.is64Bit()) PThreadInternal64(pointer) else PThreadInternal32(pointer)
        }
    }

}
