package com.vortexdbg.linux.struct

import com.vortexdbg.file.linux.StatStructure
import com.vortexdbg.unix.struct.TimeSpec32
import com.sun.jna.Pointer

class Stat32(p: Pointer?) : StatStructure(p) {

    @JvmField
    var __pad0 = ByteArray(4)
    @JvmField
    var __st_ino: Int = 0
    @JvmField
    var __pad3 = ByteArray(4)
    @JvmField
    var st_atim: TimeSpec32? = null
    @JvmField
    var st_mtim: TimeSpec32? = null
    @JvmField
    var st_ctim: TimeSpec32? = null

    override fun setSt_atim(st_atim: Long, tv_nsec: Long) {
        this.st_atim!!.tv_sec = (st_atim / 1000L).toInt()
        this.st_atim!!.tv_nsec = ((st_atim % 1000) * 1000000L + (tv_nsec % 1000000L)).toInt()
    }

    override fun setSt_mtim(st_mtim: Long, tv_nsec: Long) {
        this.st_mtim!!.tv_sec = (st_mtim / 1000L).toInt()
        this.st_mtim!!.tv_nsec = ((st_mtim % 1000) * 1000000L + tv_nsec % 1000000L).toInt()
    }

    override fun setSt_ctim(st_ctim: Long, tv_nsec: Long) {
        this.st_ctim!!.tv_sec = (st_ctim / 1000L).toInt()
        this.st_ctim!!.tv_nsec = ((st_ctim % 1000) * 1000000L + tv_nsec % 1000000L).toInt()
    }

    override fun setSt_ino(st_ino: Long) {
        super.setSt_ino(st_ino)
        __st_ino = st_ino.toInt()
    }

    override fun getFieldOrder(): List<String> {
        return listOf("st_dev", "__pad0", "__st_ino", "st_mode", "st_nlink", "st_uid", "st_gid", "st_rdev", "__pad3",
                "st_size", "st_blksize", "st_blocks", "st_atim", "st_mtim", "st_ctim", "st_ino")
    }

}
