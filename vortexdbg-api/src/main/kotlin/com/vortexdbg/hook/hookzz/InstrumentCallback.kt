package com.vortexdbg.hook.hookzz

import com.vortexdbg.Emulator
import com.vortexdbg.arm.context.RegisterContext

abstract class InstrumentCallback<T : RegisterContext> {

    abstract fun dbiCall(emulator: Emulator<*>, ctx: T, info: HookEntryInfo)

}
