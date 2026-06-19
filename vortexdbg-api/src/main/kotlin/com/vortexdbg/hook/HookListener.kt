package com.vortexdbg.hook

import com.vortexdbg.memory.SvcMemory

interface HookListener {

    /**
     * Decide whether to redirect a symbol binding.
     *
     * @return 0 to leave the binding untouched, otherwise the address calls
     *         to this symbol should be redirected to.
     */
    fun hook(svcMemory: SvcMemory, libraryName: String, symbolName: String, old: Long): Long

    companion object {
        const val EACH_BIND = -1
        const val WEAK_BIND = -2
        const val FIXUP_BIND = -3
    }

}
