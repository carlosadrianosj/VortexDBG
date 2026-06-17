package com.vortexdbg.arm

import com.vortexdbg.Emulator
import com.vortexdbg.Svc
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

abstract class Arm64Svc(private val svcName: String?) : Svc {

    constructor() : this(null)

    override fun onRegister(svcMemory: SvcMemory, svcNumber: Int): VortexdbgPointer {
        if (log.isDebugEnabled) {
            log.debug("onRegister: {}", javaClass, Exception("svcNumber=0x" + Integer.toHexString(svcNumber)))
        }

        val name = getName()
        return register(svcMemory, svcNumber, name ?: "Arm64Svc")
    }

    override fun handlePostCallback(emulator: Emulator<*>) {
    }

    override fun handlePreCallback(emulator: Emulator<*>) {
    }

    override fun getName(): String? {
        return svcName
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(Arm64Svc::class.java)

        const val SVC_MAX = 0xffff

        @JvmStatic
        fun assembleSvc(svcNumber: Int): Int {
            if (svcNumber in 0 until SVC_MAX - 1) {
                return 0xd4000001.toInt() or (svcNumber shl 5)
            } else {
                throw IllegalStateException("svcNumber=0x" + Integer.toHexString(svcNumber))
            }
        }

        private fun register(svcMemory: SvcMemory, svcNumber: Int, name: String): VortexdbgPointer {
            val buffer = ByteBuffer.allocate(8)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(assembleSvc(svcNumber)) // "svc #0x" + Integer.toHexString(svcNumber)
            buffer.putInt(0xd65f03c0.toInt()) // ret

            val code = buffer.array()
            val pointer = svcMemory.allocate(code.size, name)
            pointer.write(0L, code, 0, code.size)
            return pointer
        }
    }
}
