package com.vortexdbg.linux

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.hook.HookListener
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import net.fornwall.jelf.ElfSymbol

import java.io.IOException

class ModuleSymbol(
    @JvmField val soName: String,
    private val load_base: Long,
    @JvmField val symbol: ElfSymbol?,
    @JvmField val relocationAddr: Pointer,
    @JvmField val toSoName: String?,
    @JvmField val offset: Long
) {

    @Throws(IOException::class)
    fun resolve(modules: Collection<Module>, resolveWeak: Boolean, listeners: List<HookListener>, svcMemory: SvcMemory): ModuleSymbol? {
        val symbolName = symbol!!.getName()!!
        for (m in modules) {
            val module = m as LinuxModule
            val symbolHook = module.hookMap[symbolName]
            if (symbolHook != null) {
                return ModuleSymbol(soName, WEAK_BASE, symbol, relocationAddr, module.name, symbolHook)
            }

            val elfSymbol = module.getELFSymbolByName(symbolName)
            if (elfSymbol != null && !elfSymbol.isUndef()) {
                when (elfSymbol.getBinding()) {
                    ElfSymbol.BINDING_GLOBAL, ElfSymbol.BINDING_WEAK -> {
                        for (listener in listeners) {
                            val hook = listener.hook(svcMemory, module.name, symbolName, module.base + elfSymbol.value + offset)
                            if (hook > 0) {
                                module.hookMap[symbolName] = hook
                                return ModuleSymbol(soName, WEAK_BASE, elfSymbol, relocationAddr, module.name, hook)
                            }
                        }
                        return ModuleSymbol(soName, module.base, elfSymbol, relocationAddr, module.name, offset)
                    }
                }
            }
        }

        if (resolveWeak && symbol.getBinding() == ElfSymbol.BINDING_WEAK) {
            return ModuleSymbol(soName, WEAK_BASE, symbol, relocationAddr, "0", 0)
        }

        if ("dlopen" == symbolName ||
                "dlclose" == symbolName ||
                "dlsym" == symbolName ||
                "dlerror" == symbolName ||
                "dladdr" == symbolName ||
                "android_update_LD_LIBRARY_PATH" == symbolName ||
                "android_get_LD_LIBRARY_PATH" == symbolName ||
                "dl_iterate_phdr" == symbolName ||
                "android_dlopen_ext" == symbolName ||
                "android_set_application_target_sdk_version" == symbolName ||
                "android_get_application_target_sdk_version" == symbolName ||
                "android_init_namespaces" == symbolName ||
                "android_create_namespace" == symbolName ||
                "dlvsym" == symbolName ||
                "android_dlwarning" == symbolName ||
                "dl_unwind_find_exidx" == symbolName) {
            if (resolveWeak) {
                for (listener in listeners) {
                    val hook = listener.hook(svcMemory, "libdl.so", symbolName, offset)
                    if (hook > 0) {
                        return ModuleSymbol(soName, WEAK_BASE, symbol, relocationAddr, "libdl.so", hook)
                    }
                }
            }
        }

        return null
    }

    fun relocation(emulator: Emulator<*>, module: LinuxModule, symbol: ElfSymbol?) {
        val value: Long
        if (load_base == WEAK_BASE) {
            value = offset
        } else {
            value = module.base + (if (symbol == null) 0 else symbol.value) + offset
        }
        relocationAddr.setPointer(0L, VortexdbgPointer.pointer(emulator, value))
    }

    @Throws(IOException::class)
    fun relocation(emulator: Emulator<*>, owner: LinuxModule) {
        if (symbol != null) {
            owner.resolvedSymbols[symbol.getName()!!] = this
        }
        val value: Long
        if (load_base == WEAK_BASE) {
            value = offset
        } else {
            value = load_base + (if (symbol == null) 0 else symbol.value) + offset
        }
        relocationAddr.setPointer(0L, VortexdbgPointer.pointer(emulator, value))
    }

    fun getSymbol(): ElfSymbol? {
        return symbol
    }

    fun getRelocationAddr(): Pointer {
        return relocationAddr
    }

    companion object {
        @JvmField
        val WEAK_BASE: Long = -1
    }

}
