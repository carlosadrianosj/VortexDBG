package com.vortexdbg.linux.android.dvm.array

import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

/**
 * JNI char[] mirror. A jchar is 2 bytes (UTF-16), so the guest-memory representation is a run of
 * 2-byte shorts. We convert char[] <-> short[] at the memory boundary and use the short-typed
 * pointer ops (which write exactly 2 bytes per element), avoiding the platform wchar_t width that a
 * raw char[] transfer would use. Mirrors IntArray/ShortArray otherwise.
 */
class CharArray(vm: VM, value: kotlin.CharArray) : BaseArray<kotlin.CharArray>(vm.resolveClass("[C"), value), PrimitiveArray<kotlin.CharArray> {

    override fun length(): Int {
        return value.size
    }

    fun setValue(value: kotlin.CharArray) {
        this.value = value
    }

    override fun setData(start: Int, data: kotlin.CharArray) {
        System.arraycopy(data, 0, value, start, data.size)
    }

    override fun _GetArrayCritical(emulator: Emulator<*>, isCopy: Pointer?): VortexdbgPointer {
        if (isCopy != null) {
            isCopy.setInt(0L, VM.JNI_TRUE)
        }
        val pointer = this.allocateMemoryBlock(emulator, value.size * 2)   // jchar = 2 bytes
        val shorts = kotlin.ShortArray(value.size) { value[it].code.toShort() }
        pointer.write(0L, shorts, 0, shorts.size)
        return pointer
    }

    override fun _ReleaseArrayCritical(elems: Pointer, mode: Int) {
        when (mode) {
            VM.JNI_COMMIT -> this.setValue(readChars(elems))
            0 -> {
                this.setValue(readChars(elems))
                this.freeMemoryBlock(elems)
            }
            VM.JNI_ABORT -> this.freeMemoryBlock(elems)
        }
    }

    private fun readChars(elems: Pointer): kotlin.CharArray {
        val shorts = elems.getShortArray(0L, this.value.size)
        return kotlin.CharArray(this.value.size) { (shorts[it].toInt() and 0xFFFF).toChar() }
    }
}
