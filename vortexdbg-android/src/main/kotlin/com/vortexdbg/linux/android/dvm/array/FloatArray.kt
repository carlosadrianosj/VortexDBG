package com.vortexdbg.linux.android.dvm.array

import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

class FloatArray(vm: VM, value: kotlin.FloatArray) : BaseArray<kotlin.FloatArray>(vm.resolveClass("[F"), value), PrimitiveArray<kotlin.FloatArray> {

    override fun length(): Int {
        return value.size
    }

    fun setValue(value: kotlin.FloatArray) {
        this.value = value
    }

    override fun setData(start: Int, data: kotlin.FloatArray) {
        System.arraycopy(data, 0, value, start, data.size)
    }

    override fun _GetArrayCritical(emulator: Emulator<*>, isCopy: Pointer?): VortexdbgPointer {
        if (isCopy != null) {
            isCopy.setInt(0L, VM.JNI_TRUE)
        }
        val pointer = this.allocateMemoryBlock(emulator, value.size * 4)
        pointer.write(0L, value, 0, value.size)
        return pointer
    }

    override fun _ReleaseArrayCritical(elems: Pointer, mode: Int) {
        when (mode) {
            VM.JNI_COMMIT -> this.setValue(elems.getFloatArray(0L, this.value.size))
            0 -> {
                this.setValue(elems.getFloatArray(0L, this.value.size))
                this.freeMemoryBlock(elems)
            }
            VM.JNI_ABORT -> this.freeMemoryBlock(elems)
        }
    }
}
