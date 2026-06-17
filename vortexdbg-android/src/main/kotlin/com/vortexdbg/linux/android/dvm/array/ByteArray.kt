package com.vortexdbg.linux.android.dvm.array

import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import org.apache.commons.codec.binary.Hex

class ByteArray(vm: VM, value: kotlin.ByteArray) : BaseArray<kotlin.ByteArray>(vm.resolveClass("[B"), value), PrimitiveArray<kotlin.ByteArray> {

    override fun length(): Int {
        return value.size
    }

    fun setValue(value: kotlin.ByteArray) {
        this.value = value
    }

    override fun setData(start: Int, data: kotlin.ByteArray) {
        System.arraycopy(data, 0, value, start, data.size)
    }

    override fun _GetArrayCritical(emulator: Emulator<*>, isCopy: Pointer?): VortexdbgPointer {
        if (isCopy != null) {
            isCopy.setInt(0L, VM.JNI_TRUE)
        }
        val pointer = this.allocateMemoryBlock(emulator, value.size)
        pointer.write(0L, value, 0, value.size)
        return pointer
    }

    override fun _ReleaseArrayCritical(elems: Pointer, mode: Int) {
        when (mode) {
            VM.JNI_COMMIT -> this.setValue(elems.getByteArray(0L, this.value.size))
            0 -> {
                this.setValue(elems.getByteArray(0L, this.value.size))
                this.freeMemoryBlock(elems)
            }
            VM.JNI_ABORT -> this.freeMemoryBlock(elems)
        }
    }

    override fun toString(): String {
        return if (value != null && value.size <= 64) {
            "[B@0x" + Hex.encodeHexString(value)
        } else {
            super.toString()
        }
    }
}
