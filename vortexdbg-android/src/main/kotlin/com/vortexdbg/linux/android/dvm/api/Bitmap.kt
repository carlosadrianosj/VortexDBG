package com.vortexdbg.linux.android.dvm.api

import com.vortexdbg.Emulator
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM
import com.sun.jna.Pointer

import java.awt.image.BufferedImage
import java.nio.ByteBuffer

open class Bitmap(vm: VM, image: BufferedImage) : DvmObject<BufferedImage>(vm.resolveClass("android/graphics/Bitmap"), image) {

    open fun lockPixels(emulator: Emulator<*>, image: BufferedImage, buffer: ByteBuffer): Pointer {
        val pointer = allocateMemoryBlock(emulator, image.width * image.height * 4)
        pointer.write(0, buffer.array(), 0, buffer.capacity())
        return pointer
    }

    open fun unlockPixels() {
        freeMemoryBlock(null)
    }

}
