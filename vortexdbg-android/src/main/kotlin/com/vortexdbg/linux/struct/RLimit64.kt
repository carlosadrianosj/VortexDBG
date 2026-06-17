package com.vortexdbg.linux.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

class RLimit64(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var rlim_cur: Long = 0
    @JvmField
    var rlim_max: Long = 0

    override fun getFieldOrder(): List<String> {
        return listOf("rlim_cur", "rlim_max")
    }

}
