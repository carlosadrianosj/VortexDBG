package com.vortexdbg.unix.struct

import com.sun.jna.Pointer

class TimeSpec64(p: Pointer?) : TimeSpec(p) {

    @JvmField
    var tv_sec: Long = 0 // unsigned long
    @JvmField
    var tv_nsec: Long = 0 // long

    override fun getTvSec(): Long {
        return tv_sec
    }

    override fun getTvNsec(): Long {
        return tv_nsec
    }

    override fun setTv(tvSec: Long, tvNsec: Long) {
        this.tv_sec = tvSec
        this.tv_nsec = tvNsec
    }

    override fun getFieldOrder(): List<String> {
        return listOf("tv_sec", "tv_nsec")
    }
}
