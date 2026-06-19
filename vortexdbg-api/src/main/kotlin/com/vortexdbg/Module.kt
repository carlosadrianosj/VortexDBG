package com.vortexdbg

import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.memory.MemRegion
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.spi.InitFunctionListener
import com.vortexdbg.spi.LibraryFile

import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections

abstract class Module(
    @JvmField val name: String,
    @JvmField val base: Long,
    @JvmField val size: Long,
    @JvmField protected val neededLibraries: Map<String, Module>,
    private val regions: List<MemRegion>,
    private val libraryFile: LibraryFile?
) {

    open fun getFileSize(): Long {
        return if (libraryFile == null) 0 else libraryFile.getFileSize()
    }

    open fun getBaseHeader(): Long {
        return base
    }

    fun getRegions(): List<MemRegion> {
        return Collections.unmodifiableList(regions)
    }

    abstract fun callFunction(emulator: Emulator<*>, offset: Long, vararg args: Any?): Number

    fun callFunction(emulator: Emulator<*>, symbolName: String, vararg args: Any?): Number {
        val symbol = findSymbolByName(symbolName, false)
            ?: throw IllegalStateException("find symbol failed: $symbolName")
        if (symbol.isUndef()) {
            throw IllegalStateException("$symbolName is NOT defined")
        }

        return symbol.call(emulator, *args)
    }

    fun findSymbolByName(name: String): Symbol? {
        return findSymbolByName(name, true)
    }

    abstract fun findSymbolByName(name: String, withDependencies: Boolean): Symbol?

    abstract fun findClosestSymbolByAddress(address: Long, fast: Boolean): Symbol?

    /**
     * @return all exported/dynamic symbols in this module, empty collection if not supported
     */
    open fun getExportedSymbols(): Collection<Symbol> {
        return Collections.emptyList()
    }

    protected fun findDependencySymbolByName(name: String): Symbol? {
        for (module in neededLibraries.values) {
            val symbol = module.findSymbolByName(name, true)
            if (symbol != null) {
                return symbol
            }
        }
        return null
    }

    private var referenceCount = 0

    open fun addReferenceCount() {
        referenceCount++
    }

    open fun decrementReferenceCount(): Int {
        return --referenceCount
    }

    private var forceCallInit = false

    open fun isForceCallInit(): Boolean {
        return forceCallInit
    }

    @Suppress("unused")
    open fun setForceCallInit() {
        this.forceCallInit = true
    }

    fun unload(backend: Backend) {
        for (region in regions) {
            backend.mem_unmap(region.begin, region.end - region.begin)
        }
    }

    open fun getNeededLibraries(): Collection<Module> {
        return neededLibraries.values
    }

    open fun getDependencyModule(name: String): Module? {
        return neededLibraries[name]
    }

    @JvmField
    protected var entryPoint: Long = 0

    open fun setEntryPoint(entryPoint: Long) {
        this.entryPoint = entryPoint
    }

    abstract fun callEntry(emulator: Emulator<*>, vararg args: String): Int

    private var pathPointer: VortexdbgPointer? = null

    abstract fun getPath(): String

    /**
     * Registers a symbol so later lookups resolve [symbolName] to [address].
     *
     * @param address the symbol's resolved memory address
     */
    abstract fun registerSymbol(symbolName: String, address: Long)

    fun createPathMemory(svcMemory: SvcMemory): VortexdbgPointer {
        if (this.pathPointer == null) {
            val bytes = getPath().toByteArray(StandardCharsets.UTF_8)
            val path = Arrays.copyOf(bytes, bytes.size + 1)
            this.pathPointer = svcMemory.allocate(path.size, "Module.path: " + getPath())
            this.pathPointer!!.write(0L, path, 0, path.size)
        }
        return this.pathPointer!!
    }

    open fun isVirtual(): Boolean {
        return false
    }

    /**
     * Maps a virtual memory offset back to its offset within the backing file.
     *
     * @param offset the in-memory offset
     * @return the file offset, or -1 if no file region maps to [offset]
     */
    abstract fun virtualMemoryAddressToFileOffset(offset: Long): Int

    @JvmField
    protected var initFunctionListener: InitFunctionListener? = null

    open fun setInitFunctionListener(initFunctionListener: InitFunctionListener?) {
        this.initFunctionListener = initFunctionListener
    }

    companion object {
        @JvmStatic
        fun emulateFunction(emulator: Emulator<*>, address: Long, vararg args: Any?): Number {
            val list = ArrayList<Number>(args.size)
            for (arg in args) {
                if (arg is String) {
                    list.add(StringNumber(arg))
                } else if (arg is ByteArray) {
                    list.add(ByteArrayNumber(arg))
                } else if (arg is PointerArg) {
                    list.add(PointerNumber(arg.getPointer() as VortexdbgPointer))
                } else if (arg is Number) {
                    list.add(arg)
                } else if (arg == null) {
                    list.add(PointerNumber(null))
                } else {
                    throw IllegalStateException("Unsupported arg: $arg")
                }
            }
            return emulator.eFunc(address, *list.toTypedArray())
        }
    }

}
