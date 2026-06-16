package com.vortexdbg.arm.backend

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.hypervisor.Hypervisor
import com.vortexdbg.arm.backend.hypervisor.HypervisorBackend64
import org.scijava.nativelib.NativeLoader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.IOException

class HypervisorFactory(fallbackUnicorn: Boolean) : BackendFactory(fallbackUnicorn) {

    override fun newBackendInternal(emulator: Emulator<*>, is64Bit: Boolean): Backend {
        val hypervisor = Hypervisor(is64Bit)
        return HypervisorBackend64(emulator, hypervisor)
    }

    companion object {

        private val log: Logger = LoggerFactory.getLogger(HypervisorFactory::class.java)

        init {
            try {
                NativeLoader.loadLibrary("hypervisor")
            } catch (e: IOException) {
                log.debug("load hypervisor library failed", e)
            }
        }

        @JvmStatic external fun testVcpu()
        @JvmStatic external fun getPageSize(): Int
        @JvmStatic external fun getMaxVcpuCount(): Int
        @JvmStatic external fun sysctlInt(name: String): Int
        @JvmStatic external fun context_alloc(): Long
        @JvmStatic external fun free(context: Long)
    }

}
