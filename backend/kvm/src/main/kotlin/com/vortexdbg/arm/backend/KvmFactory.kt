package com.vortexdbg.arm.backend

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.kvm.Kvm
import com.vortexdbg.arm.backend.kvm.KvmBackend32
import com.vortexdbg.arm.backend.kvm.KvmBackend64
import org.scijava.nativelib.NativeLibraryUtil

import java.io.File
import java.io.IOException

class KvmFactory(fallbackUnicorn: Boolean) : BackendFactory(fallbackUnicorn) {

    protected override fun newBackendInternal(emulator: Emulator<*>, is64Bit: Boolean): Backend {
        if (supportKvm()) {
            val kvm = Kvm(is64Bit)
            return if (is64Bit) {
                KvmBackend64(emulator, kvm)
            } else {
                KvmBackend32(emulator, kvm)
            }
        } else {
            throw UnsupportedOperationException()
        }
    }

    companion object {
        private fun supportKvm(): Boolean {
            val kvm = File("/dev/kvm")
            return kvm.exists()
        }

        init {
            try {
                if (NativeLibraryUtil.getArchitecture() == NativeLibraryUtil.Architecture.LINUX_ARM64 &&
                    supportKvm()
                ) {
                    org.scijava.nativelib.NativeLoader.loadLibrary("kvm")
                }
            } catch (ignored: IOException) {
            }
        }
    }

}
