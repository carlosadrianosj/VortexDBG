package com.vortexdbg.linux.thread

import com.sun.jna.Pointer

class PThreadInternal32(p: Pointer?) : PThreadInternal(p) {

    @JvmField
    var next: Int = 0
    @JvmField
    var prev: Int = 0

    init {
        unpack()
    }

    override fun getFieldOrder(): List<String> {
        return listOf("next", "prev", "tid")
    }
}
