package com.vortexdbg.hook

import com.vortexdbg.Emulator
import com.vortexdbg.Family
import com.vortexdbg.Module
import com.vortexdbg.arm.Arm64Hook
import com.vortexdbg.arm.ArmHook
import com.vortexdbg.arm.HookStatus
import com.vortexdbg.arm.context.EditableArm32RegisterContext
import com.vortexdbg.arm.context.EditableArm64RegisterContext
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.spi.LibraryFile
import com.sun.jna.Pointer
import java.net.URL
import java.util.Stack

abstract class BaseHook : IHook {

    @JvmField
    protected val emulator: Emulator<*>

    @JvmField
    protected val module: Module

    constructor(emulator: Emulator<*>, libName: String) {
        this.emulator = emulator
        this.module = emulator.getMemory().load(resolveLibrary(libName))
    }

    @Suppress("unused")
    constructor(emulator: Emulator<*>, libName: String, forceCallInit: Boolean) {
        this.emulator = emulator
        this.module = emulator.getMemory().load(resolveLibrary(libName), forceCallInit)
    }

    protected fun createReplacePointer(callback: ReplaceCallback, backup: Pointer, enablePostCall: Boolean): Pointer {
        val svcMemory = emulator.getSvcMemory()
        return svcMemory.registerSvc(if (emulator.is64Bit()) object : Arm64Hook(enablePostCall) {
            private val context = Stack<Any?>()
            override fun hook(emulator: Emulator<*>): HookStatus {
                return callback.onCall(emulator, Arm64HookContext(context, emulator.getContext()), backup.getLong(0))
            }
            override fun handlePostCallback(emulator: Emulator<*>) {
                super.handlePostCallback(emulator)
                val registerContext: EditableArm64RegisterContext = emulator.getContext()
                callback.postCall(emulator, Arm64HookContext(context, registerContext))
            }
        } else object : ArmHook(enablePostCall) {
            private val context = Stack<Any?>()
            override fun hook(emulator: Emulator<*>): HookStatus {
                return callback.onCall(emulator, Arm32HookContext(context, emulator.getContext()), backup.getInt(0).toLong() and 0xffffffffL)
            }
            override fun handlePostCallback(emulator: Emulator<*>) {
                super.handlePostCallback(emulator)
                val registerContext: EditableArm32RegisterContext = emulator.getContext()
                callback.postCall(emulator, Arm32HookContext(context, registerContext))
            }
        })
    }

    protected fun resolveLibrary(libName: String): LibraryFile {
        val family = emulator.getFamily()
        val lib = libName + family.libraryExtension
        val url: URL = BaseHook::class.java.getResource(family.libraryPath + lib)
            ?: throw IllegalStateException("resolve library failed: $lib")

        return emulator.createURLibraryFile(url, lib)
    }

    override fun getModule(): Module {
        return module
    }

}
