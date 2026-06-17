package com.vortexdbg.arm

import com.vortexdbg.Emulator
import com.vortexdbg.Svc
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import java.nio.ByteBuffer
import java.nio.ByteOrder

abstract class ThumbSvc(private val svcName: String?) : Svc {

    constructor() : this(null)

    override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
        val buffer = ByteBuffer.allocate(4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(assembleSvc(svcNumber)) // svc #svcNumber
        buffer.putShort(0x4770.toShort()) // bx lr
        val code = buffer.array()
        val name = getName()
        val pointer = svcMemory.allocate(code.size, name ?: "ThumbSvc")
        pointer.write(code)
        return pointer
    }

    override fun handlePostCallback(emulator: Emulator<*>) {
    }

    override fun handlePreCallback(emulator: Emulator<*>) {
    }

    override fun getName(): String? {
        return svcName
    }

    companion object {
        const val SVC_MAX = 0xff

        @JvmStatic
        fun assembleSvc(svcNumber: Int): Short {
            if (svcNumber in 0 until SVC_MAX - 1) {
                return (0xdf00 or svcNumber).toShort()
            } else {
                throw IllegalStateException("svcNumber=0x" + Integer.toHexString(svcNumber))
            }
        }
    }
}
