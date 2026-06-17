package com.vortexdbg.linux.struct

import com.vortexdbg.file.linux.StatStructure
import com.vortexdbg.unix.struct.TimeSpec64
import com.sun.jna.Pointer

class Stat64(p: Pointer?) : StatStructure(p) {

    @JvmField
    var __pad1: Long = 0
    @JvmField
    var __pad2: Int = 0
    @JvmField
    var st_atim: TimeSpec64? = null
    @JvmField
    var st_mtim: TimeSpec64? = null
    @JvmField
    var st_ctim: TimeSpec64? = null
    @JvmField
    var __unused4: Int = 0
    @JvmField
    var __unused5: Int = 0

    override fun setSt_atim(st_atim: Long, tv_nsec: Long) {
        this.st_atim!!.tv_sec = st_atim / 1000L
        this.st_atim!!.tv_nsec = (st_atim % 1000) * 1000000L + (tv_nsec % 1000000L)
    }

    override fun setSt_mtim(st_mtim: Long, tv_nsec: Long) {
        this.st_mtim!!.tv_sec = st_mtim / 1000L
        this.st_mtim!!.tv_nsec = (st_mtim % 1000) * 1000000L + (tv_nsec % 1000000L)
    }

    override fun setSt_ctim(st_ctim: Long, tv_nsec: Long) {
        this.st_ctim!!.tv_sec = st_ctim / 1000L
        this.st_ctim!!.tv_nsec = (st_ctim % 1000) * 1000000L + (tv_nsec % 1000000L)
    }

    override fun getFieldOrder(): List<String> {
        return listOf("st_dev", "st_ino", "st_mode", "st_nlink", "st_uid", "st_gid", "st_rdev", "__pad1", "st_size", "st_blksize",
                "__pad2", "st_blocks", "st_atim", "st_mtim", "st_ctim", "__unused4", "__unused5")
    }

}
