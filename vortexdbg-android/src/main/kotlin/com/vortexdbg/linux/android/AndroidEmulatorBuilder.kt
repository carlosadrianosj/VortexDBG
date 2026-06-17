package com.vortexdbg.linux.android

import com.vortexdbg.AndroidEmulator
import com.vortexdbg.EmulatorBuilder

open class AndroidEmulatorBuilder protected constructor(is64Bit: Boolean) : EmulatorBuilder<AndroidEmulator>(is64Bit) {

    override fun build(): AndroidEmulator {
        return if (is64Bit) {
            AndroidARM64Emulator(processName, rootDir, backendFactories)
        } else {
            AndroidARMEmulator(processName, rootDir, backendFactories)
        }
    }

    companion object {
        @JvmStatic
        fun for32Bit(): AndroidEmulatorBuilder {
            return AndroidEmulatorBuilder(false)
        }

        @JvmStatic
        fun for64Bit(): AndroidEmulatorBuilder {
            return AndroidEmulatorBuilder(true)
        }
    }

}
