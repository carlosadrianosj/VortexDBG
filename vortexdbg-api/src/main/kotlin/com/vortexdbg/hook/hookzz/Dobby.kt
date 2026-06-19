package com.vortexdbg.hook.hookzz

import com.vortexdbg.Emulator
import com.vortexdbg.Svc
import com.vortexdbg.Symbol
import com.vortexdbg.arm.Arm64Svc
import com.vortexdbg.arm.ArmSvc
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.hook.BaseHook
import com.vortexdbg.hook.ReplaceCallback
import com.vortexdbg.pointer.VortexdbgPointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.Stack

/**
 * Dobby-backed [IHookZz] implementation. Prefer this on arm64; the
 * 32-bit path is only partially supported (some trampoline symbols are
 * absent and several operations throw [UnsupportedOperationException]).
 */
class Dobby private constructor(emulator: Emulator<*>) : BaseHook(emulator, "libdobby"), IHookZz {

    private val dobby_enable_near_branch_trampoline: Symbol?
    private val dobby_disable_near_branch_trampoline: Symbol?
    private val switch_to_file_log: Symbol?

    private val dobbyHook: Symbol?
    private val dobbyInstrument: Symbol?

    init {
        dobby_enable_near_branch_trampoline = module.findSymbolByName("dobby_enable_near_branch_trampoline", false)
        dobby_disable_near_branch_trampoline = module.findSymbolByName("dobby_disable_near_branch_trampoline", false)
        dobbyHook = module.findSymbolByName("DobbyHook", false)
        dobbyInstrument = module.findSymbolByName("DobbyInstrument", false)
        if (log.isDebugEnabled) {
            log.debug("dobbyHook={}, dobbyInstrument={}", dobbyHook, dobbyInstrument)
        }

        if (dobby_enable_near_branch_trampoline == null && emulator.is64Bit()) {
            throw IllegalStateException("dobby_enable_near_branch_trampoline is null")
        }
        if (dobby_disable_near_branch_trampoline == null && emulator.is64Bit()) {
            throw IllegalStateException("dobby_disable_near_branch_trampoline is null")
        }
        if (dobbyHook == null) {
            throw IllegalStateException("dobbyHook is null")
        }
        if (dobbyInstrument == null) {
            throw IllegalStateException("dobbyInstrument is null")
        }

        switch_to_file_log = module.findSymbolByName("switch_to_file_log", false)
    }

    override fun switch_to_file_log(path: String) {
        if (switch_to_file_log == null) {
            throw UnsupportedOperationException()
        }
        switch_to_file_log.call(emulator, path)
    }

    override fun enable_arm_arm64_b_branch() {
        if (dobby_enable_near_branch_trampoline == null) {
            return
        }
        val ret = dobby_enable_near_branch_trampoline.call(emulator).toInt()
        if (ret != RT_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    override fun disable_arm_arm64_b_branch() {
        if (dobby_disable_near_branch_trampoline == null) {
            return
        }
        val ret = dobby_disable_near_branch_trampoline.call(emulator).toInt()
        if (ret != RT_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    override fun replace(functionAddress: Long, callback: ReplaceCallback) {
        replace(functionAddress, callback, false)
    }

    override fun replace(symbol: Symbol, callback: ReplaceCallback) {
        replace(symbol, callback, false)
    }

    override fun replace(functionAddress: Long, svc: Svc) {
        if (svc == null) {
            throw NullPointerException()
        }
        val originCall = emulator.getMemory().malloc(emulator.getPointerSize(), false).getPointer()
        val callback = emulator.getSvcMemory().registerSvc(svc)
        val ret = dobbyHook!!.call(emulator, VortexdbgPointer.pointer(emulator, functionAddress), callback, originCall).toInt()
        if (ret != RT_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    override fun replace(symbol: Symbol, svc: Svc) {
        replace(symbol.getAddress(), svc)
    }

    override fun replace(functionAddress: Long, callback: ReplaceCallback, enablePostCall: Boolean) {
        val originCall = emulator.getMemory().malloc(emulator.getPointerSize(), false).getPointer()
        val replaceCall = createReplacePointer(callback, originCall, enablePostCall)
        val ret = dobbyHook!!.call(emulator, VortexdbgPointer.pointer(emulator, functionAddress), replaceCall, originCall).toInt()
        if (ret != RT_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    override fun replace(symbol: Symbol, callback: ReplaceCallback, enablePostCall: Boolean) {
        replace(symbol.getAddress(), callback, enablePostCall)
    }

    override fun <T : RegisterContext> wrap(symbol: Symbol, callback: WrapCallback<T>) {
        wrap(symbol.getAddress(), callback)
    }

    override fun <T : RegisterContext> wrap(functionAddress: Long, callback: WrapCallback<T>) {
        throw UnsupportedOperationException()
    }

    override fun <T : RegisterContext> instrument(symbol: Symbol, callback: InstrumentCallback<T>) {
        instrument(symbol.getAddress(), callback)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : RegisterContext> instrument(functionAddress: Long, callback: InstrumentCallback<T>) {
        val svcMemory = emulator.getSvcMemory()
        val context = Stack<Any?>()
        val dbiCall = svcMemory.registerSvc(if (emulator.is32Bit()) object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                context.clear()
                callback.dbiCall(emulator, HookZzArm32RegisterContextImpl(emulator, context) as T, ArmHookEntryInfo(emulator))
                return 0
            }
        } else object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                context.clear()
                callback.dbiCall(emulator, HookZzArm64RegisterContextImpl(emulator, context) as T, Arm64HookEntryInfo(emulator))
                return 0
            }
        })
        val ret = dobbyInstrument!!.call(emulator, VortexdbgPointer.pointer(emulator, functionAddress), dbiCall).toInt()
        if (ret != RT_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    companion object {

        private val log: Logger = LoggerFactory.getLogger(Dobby::class.java)

        @JvmStatic
        fun getInstance(emulator: Emulator<*>): Dobby {
            var dobby = emulator.get<Dobby?>(Dobby::class.java.name)
            if (dobby == null) {
                dobby = Dobby(emulator)
                emulator.set(Dobby::class.java.name, dobby)
            }
            return dobby
        }

        private const val RT_SUCCESS = 0
    }
}
