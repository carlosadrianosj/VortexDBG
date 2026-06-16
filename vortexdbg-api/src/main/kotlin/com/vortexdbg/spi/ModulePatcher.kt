package com.vortexdbg.spi

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.ModuleListener

abstract class ModulePatcher(private val path: String) : ModuleListener {

    final override fun onLoaded(emulator: Emulator<*>, module: Module) {
        if (module.getPath() == path) {
            if (emulator.is32Bit()) {
                patch32(emulator, module)
            } else {
                patch64(emulator, module)
            }
        }
    }

    protected abstract fun patch32(emulator: Emulator<*>, module: Module)
    protected abstract fun patch64(emulator: Emulator<*>, module: Module)

}
