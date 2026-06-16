package com.vortexdbg.unix.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

class TimeZone(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var tz_minuteswest: Int = 0
    @JvmField
    var tz_dsttime: Int = 0

    override fun getFieldOrder(): List<String> {
        return listOf("tz_minuteswest", "tz_dsttime")
    }
}
