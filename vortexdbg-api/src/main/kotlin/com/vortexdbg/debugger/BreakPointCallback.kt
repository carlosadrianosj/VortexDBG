package com.vortexdbg.debugger

import com.vortexdbg.Emulator

interface BreakPointCallback {

    /**
     * Invoked when the breakpoint is hit.
     * @return `false` to honor the breakpoint and stop; `true` to skip it and continue execution
     */
    fun onHit(emulator: Emulator<*>, address: Long): Boolean

}
