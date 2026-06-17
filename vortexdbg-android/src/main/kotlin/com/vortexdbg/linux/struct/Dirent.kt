package com.vortexdbg.linux.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

class Dirent(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var d_ino: Long = 0
    @JvmField
    var d_off: Long = 0
    @JvmField
    var d_reclen: Short = 0
    @JvmField
    var d_type: Byte = 0
    @JvmField
    var d_name = ByteArray(256)

    override fun getFieldOrder(): List<String> {
        return listOf("d_ino", "d_off", "d_reclen", "d_type", "d_name")
    }

}
