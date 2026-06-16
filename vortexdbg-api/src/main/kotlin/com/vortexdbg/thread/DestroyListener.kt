package com.vortexdbg.thread

import com.vortexdbg.Emulator

interface DestroyListener {

    fun onDestroy(emulator: Emulator<*>)

}
