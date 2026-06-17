package com.vortexdbg.linux.android.dvm

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer
import unicorn.Arm64Const

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class ArmVarArg64(emulator: Emulator<*>, vm: BaseVM, method: DvmMethod) : ArmVarArg(emulator, vm, method) {

    init {
        var offset = 0
        var floatOff = 0
        for (shorty in shorties) {
            when (shorty.getType()) {
                'L', 'B', 'C', 'I', 'S', 'Z' -> {
                    args.add(getInt(offset++))
                }
                'D' -> {
                    args.add(getVectorArg(floatOff++))
                }
                'F' -> {
                    args.add(getVectorArg(floatOff++).toFloat())
                }
                'J' -> {
                    val ptr = getArg(offset++)
                    args.add(if (ptr == null) 0L else ptr.peer)
                }
                else -> throw IllegalStateException("c=" + shorty.getType())
            }
        }
    }

    private fun getVectorArg(index: Int): Double {
        val buffer = ByteBuffer.allocate(16)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(emulator.getBackend().reg_read_vector(Arm64Const.UC_ARM64_REG_Q0 + index)!!)
        buffer.flip()
        return buffer.getDouble()
    }
}
