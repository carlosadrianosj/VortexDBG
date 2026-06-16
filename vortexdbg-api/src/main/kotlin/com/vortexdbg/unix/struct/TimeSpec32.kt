package com.vortexdbg.unix.struct

import com.sun.jna.Pointer

class TimeSpec32(p: Pointer?) : TimeSpec(p) {

    @JvmField
    var tv_sec: Int = 0 // unsigned long
    @JvmField
    var tv_nsec: Int = 0 // long

    override fun getTvSec(): Long {
        return tv_sec.toLong()
    }

    override fun getTvNsec(): Long {
        return tv_nsec.toLong()
    }

    override fun setTv(tvSec: Long, tvNsec: Long) {
        this.tv_sec = tvSec.toInt()
        this.tv_nsec = tvNsec.toInt()
    }

    override fun getFieldOrder(): List<String> {
        return listOf("tv_sec", "tv_nsec")
    }
}
