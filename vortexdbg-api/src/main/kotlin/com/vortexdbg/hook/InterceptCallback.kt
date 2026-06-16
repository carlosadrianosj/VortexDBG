package com.vortexdbg.hook

import com.vortexdbg.Emulator

interface InterceptCallback {

    fun onIntercept(emulator: Emulator<*>)

}
