package com.vortexdbg.linux.android.dvm.array

import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.Array
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

interface PrimitiveArray<T> : Array<T> {

    fun _GetArrayCritical(emulator: Emulator<*>, isCopy: Pointer?): VortexdbgPointer

    fun _ReleaseArrayCritical(elems: Pointer, mode: Int)

}
