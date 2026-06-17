package com.vortexdbg.arm

import com.vortexdbg.Emulator
import com.vortexdbg.arm.context.RegisterContext

class HookStatus private constructor(
    @JvmField val returnValue: Long,
    @JvmField val jump: Long,
    @JvmField val forward: Boolean
) {

    companion object {
        @JvmStatic
        fun RET(emulator: Emulator<*>, pc: Long): HookStatus {
            val context = emulator.getContext<RegisterContext>()
            return HookStatus(context.getLongArg(0), pc, true)
        }

        @JvmStatic
        fun LR(emulator: Emulator<*>, returnValue: Long): HookStatus {
            val context = emulator.getContext<RegisterContext>()
            return HookStatus(returnValue, context.getLR(), false)
        }
    }

}
