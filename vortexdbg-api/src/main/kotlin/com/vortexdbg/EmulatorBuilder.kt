package com.vortexdbg

import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.backend.BackendFactory
import java.io.File

abstract class EmulatorBuilder<T : ARMEmulator<*>> protected constructor(@JvmField protected val is64Bit: Boolean) {

    @JvmField
    protected var processName: String? = null

    fun setProcessName(processName: String?): EmulatorBuilder<T> {
        this.processName = processName
        return this
    }

    @JvmField
    protected var rootDir: File? = null

    fun setRootDir(rootDir: File?): EmulatorBuilder<T> {
        this.rootDir = rootDir
        return this
    }

    @JvmField
    protected val backendFactories: MutableList<BackendFactory> = ArrayList(5)

    fun addBackendFactory(backendFactory: BackendFactory): EmulatorBuilder<T> {
        this.backendFactories.add(backendFactory)
        return this
    }

    abstract fun build(): T

}
