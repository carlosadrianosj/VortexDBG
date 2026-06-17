package com.vortexdbg.linux.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

abstract class StatFS protected constructor(p: Pointer?) : VortexdbgStructure(p) {

    abstract fun setType(type: Int)
    abstract fun setBlockSize(size: Int)
    abstract fun setNameLen(namelen: Int)
    abstract fun setFrSize(frsize: Int)
    abstract fun setFlags(flags: Int)

    @JvmField
    var f_blocks: Long = 0
    @JvmField
    var f_bfree: Long = 0
    @JvmField
    var f_bavail: Long = 0
    @JvmField
    var f_files: Long = 0
    @JvmField
    var f_ffree: Long = 0
    @JvmField
    var f_fsid = IntArray(2)

    final override fun getFieldOrder(): List<String> {
        return listOf("f_type", "f_bsize", "f_blocks", "f_bfree", "f_bavail", "f_files", "f_ffree",
                "f_fsid", "f_namelen", "f_frsize", "f_flags", "f_spare")
    }

}
