package com.vortexdbg.arm

import com.vortexdbg.Emulator
import com.vortexdbg.pointer.VortexdbgPointer

class FunctionCall(
    @JvmField val callerAddress: Long,
    @JvmField val functionAddress: Long,
    @JvmField val returnAddress: Long,
    @JvmField val args: Array<Number>
) {

    fun toReadableString(emulator: Emulator<*>): String {
        return "FunctionCall{" +
                "callerAddress=" + VortexdbgPointer.pointer(emulator, callerAddress) +
                ", functionAddress=" + VortexdbgPointer.pointer(emulator, functionAddress) +
                ", returnAddress=" + VortexdbgPointer.pointer(emulator, returnAddress) +
                '}'
    }

}
