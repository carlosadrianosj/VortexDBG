package com.vortexdbg.linux.android

import com.vortexdbg.Emulator
import com.vortexdbg.Symbol
import com.vortexdbg.hook.BaseHook
import com.vortexdbg.hook.ReplaceCallback
import com.vortexdbg.hook.xhook.IxHook
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class XHookImpl private constructor(emulator: Emulator<*>) : BaseHook(emulator, "libxhook"), IxHook {

    private val xhook_register: Symbol
    private val xhook_refresh: Symbol

    init {
        xhook_register = module.findSymbolByName("xhook_register", false)
            ?: throw IllegalStateException("xhook_register is null")
        xhook_refresh = module.findSymbolByName("xhook_refresh", false)
            ?: throw IllegalStateException("xhook_refresh is null")
        if (log.isDebugEnabled) {
            log.debug("xhook_register={}, xhook_refresh={}", xhook_register, xhook_refresh)
        }

        val xhook_enable_sigsegv_protection = module.findSymbolByName("xhook_enable_sigsegv_protection", false)
        if (xhook_enable_sigsegv_protection == null) {
            throw IllegalStateException("xhook_enable_sigsegv_protection is null")
        } else {
            xhook_enable_sigsegv_protection.call(emulator, 0)
        }

        val xhook_enable_debug = module.findSymbolByName("xhook_enable_debug", false)
        if (xhook_enable_debug == null) {
            throw IllegalStateException("xhook_enable_debug is null")
        } else {
            xhook_enable_debug.call(emulator, if (log.isDebugEnabled) 1 else 0)
        }
    }

    override fun register(pathname_regex_str: String, symbol: String, callback: ReplaceCallback) {
        register(pathname_regex_str, symbol, callback, false)
    }

    override fun register(pathname_regex_str: String, symbol: String, callback: ReplaceCallback, enablePostCall: Boolean) {
        val old_func: Pointer = emulator.getMemory().malloc(emulator.getPointerSize(), false).getPointer().getPointer()
        val new_func = createReplacePointer(callback, old_func, enablePostCall)
        val ret = xhook_register.call(emulator, pathname_regex_str, symbol, new_func, old_func).toInt()
        if (ret != IxHook.RET_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    override fun refresh() {
        val ret = xhook_refresh.call(emulator, 0).toInt()
        if (ret != IxHook.RET_SUCCESS) {
            throw IllegalStateException("ret=$ret")
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(XHookImpl::class.java)

        @JvmStatic
        fun getInstance(emulator: Emulator<*>): IxHook {
            var ixHook = emulator.get<IxHook>(XHookImpl::class.java.name)
            if (ixHook == null) {
                ixHook = XHookImpl(emulator)
                emulator.set(XHookImpl::class.java.name, ixHook)
            }
            return ixHook
        }
    }
}
