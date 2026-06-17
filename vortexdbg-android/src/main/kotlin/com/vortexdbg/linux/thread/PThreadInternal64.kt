package com.vortexdbg.linux.thread

import com.sun.jna.Pointer

class PThreadInternal64(p: Pointer?) : PThreadInternal(p) {

    @JvmField
    var next: Long = 0
    @JvmField
    var prev: Long = 0

    init {
        unpack()
    }

    override fun getFieldOrder(): List<String> {
        return listOf("next", "prev", "tid")
    }
}
