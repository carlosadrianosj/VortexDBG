package com.vortexdbg.unix.struct

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

abstract class TimeSpec(p: Pointer?) : VortexdbgStructure(p) {

    abstract fun getTvSec(): Long
    abstract fun getTvNsec(): Long

    fun toMillis(): Long {
        return getTvSec() * 1000L + getTvNsec() / 1000000L
    }

    fun setMillis(millis: Long) {
        var millis = millis
        if (millis < 0) {
            millis = 0
        }
        val tvSec = millis / 1000L
        val tvNsec = millis % 1000L * 1000000L

        setTv(tvSec, tvNsec)
    }

    protected abstract fun setTv(tvSec: Long, tvNsec: Long)

    companion object {
        @JvmStatic
        fun createTimeSpec(emulator: Emulator<*>, ptr: Pointer?): TimeSpec? {
            if (ptr == null) {
                return null
            }
            val timeSpec = if (emulator.is32Bit()) TimeSpec32(ptr) else TimeSpec64(ptr)
            timeSpec.unpack()
            return timeSpec
        }
    }
}
