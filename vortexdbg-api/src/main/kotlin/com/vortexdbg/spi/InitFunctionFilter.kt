package com.vortexdbg.spi

import com.vortexdbg.Emulator

interface InitFunctionFilter {

    fun accept(emulator: Emulator<*>, address: Long): Boolean

}
