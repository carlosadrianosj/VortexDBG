package com.vortexdbg.hook.whale

import com.vortexdbg.Emulator
import com.vortexdbg.Svc
import com.vortexdbg.Symbol
import com.vortexdbg.hook.BaseHook
import com.vortexdbg.hook.ReplaceCallback
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

class Whale private constructor(emulator: Emulator<*>) : BaseHook(emulator, "libwhale"), IWhale {

    private val WInlineHookFunction: Symbol
    private val WImportHookFunction: Symbol

    init {
        WInlineHookFunction = module.findSymbolByName("WInlineHookFunction", false)
        WImportHookFunction = module.findSymbolByName("WImportHookFunction", false)
        if (log.isDebugEnabled) {
            log.debug("WInlineHookFunction={}, WImportHookFunction={}", WInlineHookFunction, WImportHookFunction)
        }

        if (WInlineHookFunction == null) {
            throw IllegalStateException("WInlineHookFunction is null")
        }
        if (WImportHookFunction == null) {
            throw IllegalStateException("WImportHookFunction is null")
        }
    }

    override fun inlineHookFunction(address: Long, callback: ReplaceCallback) {
        inlineHookFunction(address, callback, false)
    }

    override fun inlineHookFunction(symbol: Symbol, callback: ReplaceCallback) {
        inlineHookFunction(symbol.getAddress(), callback)
    }

    override fun replace(functionAddress: Long, callback: ReplaceCallback) {
        inlineHookFunction(functionAddress, callback)
    }

    override fun replace(symbol: Symbol, callback: ReplaceCallback) {
        inlineHookFunction(symbol, callback)
    }

    override fun replace(functionAddress: Long, callback: ReplaceCallback, enablePostCall: Boolean) {
        inlineHookFunction(functionAddress, callback, enablePostCall)
    }

    override fun replace(symbol: Symbol, callback: ReplaceCallback, enablePostCall: Boolean) {
        inlineHookFunction(symbol, callback, enablePostCall)
    }

    override fun inlineHookFunction(address: Long, callback: ReplaceCallback, enablePostCall: Boolean) {
        val backup = emulator.getMemory().malloc(emulator.getPointerSize(), false).getPointer()
        val replace = createReplacePointer(callback, backup, enablePostCall)
        WInlineHookFunction.call(emulator, VortexdbgPointer.pointer(emulator, address), replace, backup)
    }

    override fun replace(functionAddress: Long, svc: Svc) {
        if (svc == null) {
            throw NullPointerException()
        }
        val originCall = emulator.getMemory().malloc(emulator.getPointerSize(), false).getPointer()
        val callback = emulator.getSvcMemory().registerSvc(svc)
        WInlineHookFunction.call(emulator, VortexdbgPointer.pointer(emulator, functionAddress), callback, originCall)
    }

    override fun replace(symbol: Symbol, svc: Svc) {
        replace(symbol.getAddress(), svc)
    }

    override fun inlineHookFunction(symbol: Symbol, callback: ReplaceCallback, enablePostCall: Boolean) {
        inlineHookFunction(symbol.getAddress(), callback, enablePostCall)
    }

    override fun importHookFunction(symbol: String, callback: ReplaceCallback) {
        importHookFunction(symbol, callback, false)
    }

    override fun importHookFunction(symbol: String, callback: ReplaceCallback, enablePostCall: Boolean) {
        val backup = emulator.getMemory().malloc(emulator.getPointerSize(), false).getPointer()
        val replace = createReplacePointer(callback, backup, enablePostCall)
        WImportHookFunction.call(emulator, symbol, null, replace, backup)
    }

    companion object {
        private val log = LoggerFactory.getLogger(Whale::class.java)

        @JvmStatic
        fun getInstance(emulator: Emulator<*>): IWhale {
            var whale = emulator.get<IWhale>(Whale::class.java.name)
            if (whale == null) {
                whale = Whale(emulator)
                emulator.set(Whale::class.java.name, whale)
            }
            return whale
        }
    }
}
