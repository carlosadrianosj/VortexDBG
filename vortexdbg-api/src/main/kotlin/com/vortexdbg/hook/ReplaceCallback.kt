package com.vortexdbg.hook

import com.vortexdbg.Emulator
import com.vortexdbg.arm.HookStatus

abstract class ReplaceCallback {

    open fun onCall(emulator: Emulator<*>, originFunction: Long): HookStatus {
        return HookStatus.RET(emulator, originFunction)
    }

    open fun onCall(emulator: Emulator<*>, context: HookContext, originFunction: Long): HookStatus {
        return onCall(emulator, originFunction)
    }

    open fun postCall(emulator: Emulator<*>, context: HookContext) {
    }

}
