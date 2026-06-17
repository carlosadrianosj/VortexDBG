package com.vortexdbg.linux.struct

import com.sun.jna.Pointer

class StatFS64(p: Pointer?) : StatFS(p) {

    @JvmField
    var f_type: Long = 0
    @JvmField
    var f_bsize: Long = 0
    @JvmField
    var f_namelen: Long = 0
    @JvmField
    var f_frsize: Long = 0
    @JvmField
    var f_flags: Long = 0
    @JvmField
    var f_spare = LongArray(4)

    override fun setType(type: Int) {
        f_type = type.toLong()
    }

    override fun setBlockSize(size: Int) {
        this.f_bsize = size.toLong()
    }

    override fun setNameLen(namelen: Int) {
        this.f_namelen = namelen.toLong()
    }

    override fun setFrSize(frsize: Int) {
        this.f_frsize = frsize.toLong()
    }

    override fun setFlags(flags: Int) {
        this.f_flags = flags.toLong()
    }

}
