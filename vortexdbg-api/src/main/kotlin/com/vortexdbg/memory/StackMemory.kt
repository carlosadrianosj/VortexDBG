package com.vortexdbg.memory

import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.serialize.Serializable

interface StackMemory : Serializable {

    fun writeStackString(str: String): VortexdbgPointer
    fun writeStackBytes(data: ByteArray): VortexdbgPointer

}
