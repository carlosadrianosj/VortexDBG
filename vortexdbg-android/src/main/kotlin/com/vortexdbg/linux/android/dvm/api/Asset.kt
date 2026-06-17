package com.vortexdbg.linux.android.dvm.api

import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

open class Asset(vm: VM, value: String) : DvmObject<String>(vm.resolveClass("android/content/res/Asset"), value) {

    open fun open(emulator: Emulator<*>, data: ByteArray) {
        val pointer = allocateMemoryBlock(emulator, data.size + 8)
        pointer.setInt(0, 0) // index
        pointer.setInt(4, data.size)
        pointer.write(8, data, 0, data.size)
    }

    open fun close() {
        freeMemoryBlock(null)
    }

    open fun getBuffer(): VortexdbgPointer {
        return memoryBlock!!.getPointer().share(8, 0)
    }

    open fun getLength(): Int {
        return memoryBlock!!.getPointer().getInt(4)
    }

    open fun read(count: Int): ByteArray {
        val pointer = memoryBlock!!.getPointer()
        val index = pointer.getInt(0)
        val length = pointer.getInt(4)
        val data = pointer.share(8, 0)
        val remaining = length - index
        val read = Math.min(remaining, count)
        pointer.setInt(0, index + read)
        return data.getByteArray(index.toLong(), read)
    }

}
