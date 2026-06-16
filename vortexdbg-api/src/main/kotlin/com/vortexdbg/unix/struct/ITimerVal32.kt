package com.vortexdbg.unix.struct

import com.vortexdbg.pointer.VortexdbgStructure
import com.sun.jna.Pointer

class ITimerVal32(p: Pointer?) : VortexdbgStructure(p) {

    @JvmField
    var it_interval: TimeVal32? = null /* timer interval */
    @JvmField
    var it_value: TimeVal32? = null /* current value */

    override fun getFieldOrder(): List<String> {
        return listOf("it_interval", "it_value")
    }
}
