package com.vortexdbg.linux.struct

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

abstract class IFConf(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var ifc_len: Int = 0

    abstract fun getIfcuReq(): Long

    companion object {
        @JvmStatic
        fun create(emulator: Emulator<*>, pointer: Pointer?): IFConf {
            return if (emulator.is64Bit()) IFConf64(pointer) else IFConf32(pointer)
        }
    }

}
