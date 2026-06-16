package com.vortexdbg.arm.backend

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.dynarmic.Dynarmic
import com.vortexdbg.arm.backend.dynarmic.DynarmicBackend32
import com.vortexdbg.arm.backend.dynarmic.DynarmicBackend64

import java.io.IOException

class DynarmicFactory(fallbackUnicorn: Boolean) : BackendFactory(fallbackUnicorn) {

    override protected fun newBackendInternal(emulator: Emulator<*>, is64Bit: Boolean): Backend {
        val dynarmic = Dynarmic(is64Bit)
        return if (is64Bit) DynarmicBackend64(emulator, dynarmic) else DynarmicBackend32(emulator, dynarmic)
    }

    companion object {
        init {
            try {
                org.scijava.nativelib.NativeLoader.loadLibrary("dynarmic")
            } catch (ignored: IOException) {
            }
        }
    }

}
