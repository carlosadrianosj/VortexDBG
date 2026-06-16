package com.vortexdbg.hook

import com.vortexdbg.memory.SvcMemory

interface HookListener {

    /**
     * 返回0表示没有hook，否则返回hook以后的调用地址
     */
    fun hook(svcMemory: SvcMemory, libraryName: String, symbolName: String, old: Long): Long

    companion object {
        const val EACH_BIND = -1
        const val WEAK_BIND = -2
        const val FIXUP_BIND = -3
    }

}
