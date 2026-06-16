package com.vortexdbg.hook

import com.vortexdbg.Emulator

interface HookCallback {

    fun onHook(emulator: Emulator<*>): Int

}
