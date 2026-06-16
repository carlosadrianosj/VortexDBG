package com.vortexdbg.spi

import com.vortexdbg.Emulator

abstract class InitFunction(
    @JvmField protected val load_base: Long,
    @JvmField protected val libName: String,
    @JvmField protected val address: Long
) {

    abstract fun getAddress(): Long

    abstract fun call(emulator: Emulator<*>): Long

}
