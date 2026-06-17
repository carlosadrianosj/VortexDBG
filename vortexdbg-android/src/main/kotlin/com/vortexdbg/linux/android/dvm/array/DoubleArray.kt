package com.vortexdbg.linux.android.dvm.array

import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

class DoubleArray(vm: VM, value: kotlin.DoubleArray) : BaseArray<kotlin.DoubleArray>(vm.resolveClass("[D"), value), PrimitiveArray<kotlin.DoubleArray> {

    override fun length(): Int {
        return value.size
    }

    fun setValue(value: kotlin.DoubleArray) {
        this.value = value
    }

    override fun setData(start: Int, data: kotlin.DoubleArray) {
        System.arraycopy(data, 0, value, start, data.size)
    }

    override fun _GetArrayCritical(emulator: Emulator<*>, isCopy: Pointer?): VortexdbgPointer {
        if (isCopy != null) {
            isCopy.setInt(0L, VM.JNI_TRUE)
        }
        val pointer = this.allocateMemoryBlock(emulator, value.size * 8)
        pointer.write(0L, value, 0, value.size)
        return pointer
    }

    override fun _ReleaseArrayCritical(elems: Pointer, mode: Int) {
        when (mode) {
            VM.JNI_COMMIT -> this.setValue(elems.getDoubleArray(0L, this.value.size))
            0 -> {
                this.setValue(elems.getDoubleArray(0L, this.value.size))
                this.freeMemoryBlock(elems)
            }
            VM.JNI_ABORT -> this.freeMemoryBlock(elems)
        }
    }
}
