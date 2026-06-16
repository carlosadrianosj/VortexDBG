package com.vortexdbg.memory

import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

interface MemoryBlock {

    fun getPointer(): VortexdbgPointer

    fun isSame(pointer: Pointer): Boolean

    fun free()

}
