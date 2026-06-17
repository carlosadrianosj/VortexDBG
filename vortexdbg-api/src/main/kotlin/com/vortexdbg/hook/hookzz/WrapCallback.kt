package com.vortexdbg.hook.hookzz

import com.vortexdbg.Emulator
import com.vortexdbg.arm.context.RegisterContext

abstract class WrapCallback<T : RegisterContext> {

    abstract fun preCall(emulator: Emulator<*>, ctx: T, info: HookEntryInfo)

    open fun postCall(emulator: Emulator<*>, ctx: T, info: HookEntryInfo) {}

}
