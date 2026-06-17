package com.vortexdbg.spi

import com.vortexdbg.LibraryResolver
import com.vortexdbg.Module
import com.vortexdbg.ModuleListener
import com.vortexdbg.Symbol
import com.vortexdbg.hook.HookListener
import com.vortexdbg.pointer.VortexdbgPointer

import java.io.File

@Suppress("unused")
interface Loader {

    fun setLibraryResolver(libraryResolver: LibraryResolver)

    fun disableCallInitFunction()

    fun setCallInitFunction(callInit: Boolean)

    fun load(elfFile: File): Module
    fun load(elfFile: File, forceCallInit: Boolean): Module

    fun load(libraryFile: LibraryFile): Module
    fun load(libraryFile: LibraryFile, forceCallInit: Boolean): Module

    fun findModuleByAddress(address: Long): Module?
    fun findModule(name: String): Module?

    fun dlopen(filename: String): Module?
    fun dlopen(filename: String, calInit: Boolean): Module?
    fun dlclose(handle: Long): Boolean
    fun dlsym(handle: Long, symbol: String): Symbol?

    fun addModuleListener(listener: ModuleListener)

    fun addHookListener(listener: HookListener)

    fun getLoadedModules(): Collection<Module>

    fun getMaxLengthLibraryName(): String
    fun getMaxSizeOfLibrary(): Long

    /**
     * 加载虚拟模块
     */
    @JvmSuppressWildcards
    fun loadVirtualModule(name: String, symbols: Map<String, VortexdbgPointer>): Module

}
