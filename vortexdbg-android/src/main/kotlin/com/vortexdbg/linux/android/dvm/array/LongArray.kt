package com.vortexdbg.linux.android.dvm.array

import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

/**
 * JNI long[] mirror. A jlong is 8 bytes, matching kotlin.Long, so the guest-memory representation is
 * a direct 8-byte-per-element copy (no width conversion). Mirrors IntArray otherwise.
 */
class LongArray(vm: VM, value: kotlin.LongArray) : BaseArray<kotlin.LongArray>(vm.resolveClass("[J"), value), PrimitiveArray<kotlin.LongArray> {

    override fun length(): Int {
        return value.size
    }

    fun setValue(value: kotlin.LongArray) {
        this.value = value
    }

    override fun setData(start: Int, data: kotlin.LongArray) {
        System.arraycopy(data, 0, value, start, data.size)
    }

    override fun _GetArrayCritical(emulator: Emulator<*>, isCopy: Pointer?): VortexdbgPointer {
        if (isCopy != null) {
            isCopy.setInt(0L, VM.JNI_TRUE)
        }
        val pointer = this.allocateMemoryBlock(emulator, value.size * 8)   // jlong = 8 bytes
        pointer.write(0L, value, 0, value.size)
        return pointer
    }

    override fun _ReleaseArrayCritical(elems: Pointer, mode: Int) {
        when (mode) {
            VM.JNI_COMMIT -> this.setValue(elems.getLongArray(0L, this.value.size))
            0 -> {
                this.setValue(elems.getLongArray(0L, this.value.size))
                this.freeMemoryBlock(elems)
            }
            VM.JNI_ABORT -> this.freeMemoryBlock(elems)
        }
    }
}
