package com.vortexdbg.arm

import com.vortexdbg.Emulator
import com.vortexdbg.Svc
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import java.nio.ByteBuffer
import java.nio.ByteOrder

abstract class ArmSvc(private val svcName: String?) : Svc {

    constructor() : this(null)

    override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
        val buffer = ByteBuffer.allocate(8)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(assembleSvc(svcNumber)) // svc #svcNumber
        buffer.putInt(0xe12fff1e.toInt()) // bx lr
        val code = buffer.array()
        val name = getName()
        val pointer = svcMemory.allocate(code.size, name ?: "ArmSvc")
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
        const val SVC_MAX = 0xffffff

        @JvmStatic
        fun assembleSvc(svcNumber: Int): Int {
            if (svcNumber in 0 until SVC_MAX - 1) {
                return 0xef000000.toInt() or svcNumber
            } else {
                throw IllegalStateException("svcNumber=0x" + Integer.toHexString(svcNumber))
            }
        }
    }
}
