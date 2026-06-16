package com.vortexdbg.arm.backend

import com.vortexdbg.Emulator
import java.io.IOException

class Unicorn2Factory(fallbackUnicorn: Boolean) : BackendFactory(fallbackUnicorn) {

    override fun newBackendInternal(emulator: Emulator<*>, is64Bit: Boolean): Backend {
        return Unicorn2Backend(emulator, is64Bit)
    }

    companion object {
        init {
            try {
                org.scijava.nativelib.NativeLoader.loadLibrary("unicorn")
            } catch (ignored: IOException) {
            }
        }
    }

}
