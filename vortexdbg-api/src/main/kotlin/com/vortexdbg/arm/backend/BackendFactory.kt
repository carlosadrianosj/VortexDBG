package com.vortexdbg.arm.backend

import com.vortexdbg.Emulator
import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class BackendFactory protected constructor(private val fallbackUnicorn: Boolean) {

    private fun newBackend(emulator: Emulator<*>, is64Bit: Boolean): Backend? {
        try {
            return newBackendInternal(emulator, is64Bit)
        } catch (e: Throwable) {
            log.trace("newBackend failed", e)
            if (fallbackUnicorn) {
                return null
            } else {
                throw e
            }
        }
    }

    protected abstract fun newBackendInternal(emulator: Emulator<*>, is64Bit: Boolean): Backend

    companion object {

        private val log: Logger = LoggerFactory.getLogger(BackendFactory::class.java)

        @JvmStatic
        fun createBackend(emulator: Emulator<*>, is64Bit: Boolean, backendFactories: Collection<BackendFactory>?): Backend {
            log.trace("create backend: is64Bit={}, backendFactories={}", is64Bit, backendFactories)
            if (backendFactories != null) {
                for (factory in backendFactories) {
                    val backend = factory.newBackend(emulator, is64Bit)
                    if (backend != null) {
                        return backend
                    }
                }
            }
            return UnicornBackend(emulator, is64Bit)
        }

    }

}
