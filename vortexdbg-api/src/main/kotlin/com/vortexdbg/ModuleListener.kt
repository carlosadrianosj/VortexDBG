package com.vortexdbg

interface ModuleListener {

    fun onLoaded(emulator: Emulator<*>, module: Module)

}
