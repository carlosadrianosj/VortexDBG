package com.vortexdbg.linux.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

class SysInfo32(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var uptime: Int = 0
    @JvmField
    var loads = IntArray(3)
    @JvmField
    var totalRam: Int = 0
    @JvmField
    var freeRam: Int = 0
    @JvmField
    var sharedRam: Int = 0
    @JvmField
    var bufferRam: Int = 0
    @JvmField
    var totalSwap: Int = 0
    @JvmField
    var freeSwap: Int = 0
    @JvmField
    var procs: Short = 0
    @JvmField
    var pad: Short = 0
    @JvmField
    var totalHigh: Int = 0
    @JvmField
    var freeHigh: Int = 0
    @JvmField
    var mem_unit: Int = 0
    @JvmField
    var _f = ByteArray(8)

    override fun getFieldOrder(): List<String> {
        return listOf("uptime", "loads", "totalRam", "freeRam", "sharedRam", "bufferRam", "totalSwap", "freeSwap", "procs", "pad", "totalHigh", "freeHigh", "mem_unit", "_f")
    }
}
