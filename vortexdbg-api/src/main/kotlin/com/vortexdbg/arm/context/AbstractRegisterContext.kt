package com.vortexdbg.arm.context

import com.vortexdbg.pointer.VortexdbgPointer

abstract class AbstractRegisterContext : RegisterContext {

    final override fun getIntArg(index: Int): Int {
        return getLongArg(index).toInt()
    }

    final override fun getLongArg(index: Int): Long {
        val pointer: VortexdbgPointer? = getPointerArg(index)
        return if (pointer == null) 0 else pointer.peer
    }

}
