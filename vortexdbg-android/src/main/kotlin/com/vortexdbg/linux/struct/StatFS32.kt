package com.vortexdbg.linux.struct

import com.sun.jna.Pointer

class StatFS32(p: Pointer?) : StatFS(p) {

    @JvmField
    var f_type: Int = 0
    @JvmField
    var f_bsize: Int = 0
    @JvmField
    var f_namelen: Int = 0
    @JvmField
    var f_frsize: Int = 0
    @JvmField
    var f_flags: Int = 0
    @JvmField
    var f_spare = IntArray(4)

    override fun setType(type: Int) {
        f_type = type
    }

    override fun setBlockSize(size: Int) {
        this.f_bsize = size
    }

    override fun setNameLen(namelen: Int) {
        this.f_namelen = namelen
    }

    override fun setFrSize(frsize: Int) {
        this.f_frsize = frsize
    }

    override fun setFlags(flags: Int) {
        this.f_flags = flags
    }
}
