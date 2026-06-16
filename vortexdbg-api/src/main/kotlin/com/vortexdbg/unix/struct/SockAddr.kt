package com.vortexdbg.unix.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

class SockAddr(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var sin_family: Short = 0
    @JvmField
    var sin_port: Short = 0
    @JvmField
    var sin_addr: ByteArray = ByteArray(24)

    override fun getFieldOrder(): List<String> {
        return listOf("sin_family", "sin_port", "sin_addr")
    }
}
