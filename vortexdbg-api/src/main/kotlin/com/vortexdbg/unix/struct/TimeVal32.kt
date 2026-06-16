package com.vortexdbg.unix.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

class TimeVal32(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var tv_sec: Int = 0
    @JvmField
    var tv_usec: Int = 0

    override fun getFieldOrder(): List<String> {
        return listOf("tv_sec", "tv_usec")
    }
}
