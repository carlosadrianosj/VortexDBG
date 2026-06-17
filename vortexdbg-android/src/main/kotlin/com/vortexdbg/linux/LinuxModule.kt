package com.vortexdbg.linux

import com.vortexdbg.Alignment
import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Symbol
import com.vortexdbg.arm.ARM
import com.vortexdbg.memory.MemRegion
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.spi.InitFunction
import com.vortexdbg.spi.InitFunctionFilter
import com.vortexdbg.spi.LibraryFile
import com.vortexdbg.utils.Inspector
import com.vortexdbg.virtualmodule.VirtualSymbol
import com.sun.jna.Pointer
import net.fornwall.jelf.ArmExIdx
import net.fornwall.jelf.ElfDynamicStructure
import net.fornwall.jelf.ElfException
import net.fornwall.jelf.ElfFile
import net.fornwall.jelf.ElfSection
import net.fornwall.jelf.ElfSymbol
import net.fornwall.jelf.GnuEhFrameHeader
import net.fornwall.jelf.MemoizedObject
import net.fornwall.jelf.SymbolLocator
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.IOException
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap

open class LinuxModule(
    @JvmField val virtualBase: Long,
    base: Long,
    size: Long,
    name: String,
    private val dynsym: SymbolLocator?,
    private val unresolvedSymbol: MutableList<ModuleSymbol>,
    @JvmField val initFunctionList: MutableList<InitFunction>,
    neededLibraries: Map<String, Module>,
    regions: List<MemRegion>,
    @JvmField val armExIdx: MemoizedObject<ArmExIdx>?,
    @JvmField val ehFrameHeader: MemoizedObject<GnuEhFrameHeader>?,
    private val symbolTableSection: ElfSection?,
    @JvmField val elfFile: ElfFile?,
    @JvmField val dynamicStructure: ElfDynamicStructure?,
    libraryFile: LibraryFile?,
    private val initFunctionFilter: InitFunctionFilter?
) : Module(name, base, size, neededLibraries, regions, libraryFile) {

    override fun virtualMemoryAddressToFileOffset(offset: Long): Int {
        try {
            return elfFile!!.virtualMemoryAddrToFileOffset(offset).toInt()
        } catch (e: ElfException) {
            return -1
        } catch (e: IOException) {
            throw IllegalStateException("virtualMemoryAddressToFileOffset offset=0x" + java.lang.Long.toHexString(offset))
        }
    }

    @Throws(IOException::class)
    fun callInitFunction(emulator: Emulator<*>, mustCallInit: Boolean) {
        if (!mustCallInit && !unresolvedSymbol.isEmpty()) {
            for (moduleSymbol in unresolvedSymbol) {
                log.info("[{}]{} symbol is missing before init relocationAddr={}", name, moduleSymbol.getSymbol()!!.getName(), moduleSymbol.getRelocationAddr())
            }
            return
        }

        var index = 0
        while (!initFunctionList.isEmpty()) {
            val initFunction = initFunctionList.removeAt(0)
            var initAddress = initFunction.getAddress()
            if (initFunctionFilter != null && !initFunctionFilter.accept(emulator, initAddress)) {
                continue
            }
            if (initFunctionListener != null) {
                initFunctionListener!!.onPreCallInitFunction(this, initAddress, index)
            }
            initAddress = initFunction.call(emulator)
            if (initFunctionListener != null) {
                initFunctionListener!!.onPostCallInitFunction(this, initAddress, index)
            }
            index++
        }
    }

    fun getUnresolvedSymbol(): MutableList<ModuleSymbol> {
        return unresolvedSymbol
    }

    @JvmField
    val resolvedSymbols: MutableMap<String, ModuleSymbol> = HashMap()

    override fun findSymbolByName(name: String, withDependencies: Boolean): Symbol? {
        try {
            val elfSymbol = dynsym!!.getELFSymbolByName(name)
            if (elfSymbol != null && !elfSymbol.isUndef()) {
                return LinuxSymbol(this, elfSymbol)
            }

            if (withDependencies) {
                return findDependencySymbolByName(name)
            }
            return null
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    @Throws(IOException::class)
    open fun getELFSymbolByName(name: String): ElfSymbol? {
        return dynsym!!.getELFSymbolByName(name)
    }

    override fun findClosestSymbolByAddress(address: Long, fast: Boolean): Symbol? {
        try {
            val soaddr = address - base
            if (soaddr <= 0) {
                return null
            }
            var elfSymbol = if (dynsym == null) null else dynsym.getELFSymbolByAddr(soaddr)
            if (symbolTableSection != null && elfSymbol == null) {
                elfSymbol = symbolTableSection.getELFSymbolByAddr(soaddr)
            }
            var symbol: Symbol? = null
            if (elfSymbol != null) {
                symbol = LinuxSymbol(this, elfSymbol)
            }
            val entry = base + entryPoint
            if (address >= entry && (symbol == null || entry > symbol.getAddress())) {
                symbol = VirtualSymbol("start", this, entry)
            }
            return symbol
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun callEntry(emulator: Emulator<*>, vararg args: String): Int {
        if (entryPoint <= 0) {
            throw IllegalStateException("Invalid entry point")
        }

        val memory = emulator.getMemory()
        val stack = memory.allocateStack(0)

        var argc = 0
        val argv = ArrayList<Pointer>()

        argv.add(memory.writeStackString(emulator.getProcessName()))
        argc++

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            argv.add(memory.writeStackString(arg))
            argc++
            i++
        }

        if (argc % 2 != 0) { // alignment sp
            memory.allocateStack(emulator.getPointerSize())
        }

        val auxvPointer = memory.allocateStack(emulator.getPointerSize())
        assert(auxvPointer != null)
        auxvPointer.setPointer(0L, null)

        val envPointer = memory.allocateStack(emulator.getPointerSize())
        assert(envPointer != null)
        envPointer.setPointer(0L, null)

        var pointer = memory.allocateStack(emulator.getPointerSize())
        assert(pointer != null)
        pointer.setPointer(0L, null) // NULL-terminated argv

        Collections.reverse(argv)
        for (arg in argv) {
            pointer = memory.allocateStack(emulator.getPointerSize())
            assert(pointer != null)
            pointer.setPointer(0L, arg)
        }

        val kernelArgumentBlock = memory.allocateStack(emulator.getPointerSize())
        assert(kernelArgumentBlock != null)
        kernelArgumentBlock.setInt(0L, argc)

        if (log.isDebugEnabled) {
            val sp = memory.allocateStack(0)
            val data = sp.getByteArray(0L, (stack.peer - sp.peer).toInt())
            Inspector.inspect(data, "kernelArgumentBlock=" + kernelArgumentBlock + ", envPointer=" + envPointer + ", auxvPointer=" + auxvPointer)
        }

        return emulator.eEntry(base + entryPoint, kernelArgumentBlock.peer).toInt()
    }

    override fun callFunction(emulator: Emulator<*>, offset: Long, vararg args: Any?): Number {
        return emulateFunction(emulator, base + offset, *args)
    }

    override fun getPath(): String {
        return name
    }

    @JvmField
    val hookMap: MutableMap<String, Long> = HashMap()

    override fun registerSymbol(symbolName: String, address: Long) {
        hookMap[symbolName] = address
    }

    override fun getExportedSymbols(): Collection<Symbol> {
        try {
            val section = elfFile!!.getDynamicSymbolTableSection() ?: return Collections.emptyList()
            val result = ArrayList<Symbol>()
            for (i in 0 until section.getNumberOfSymbols()) {
                val elfSym = section.getELFSymbol(i)
                val symName = elfSym?.getName()
                if (elfSym != null && !elfSym.isUndef() && symName != null && !symName.isEmpty()) {
                    result.add(LinuxSymbol(this, elfSym))
                }
            }
            return result
        } catch (e: IOException) {
            return Collections.emptyList()
        }
    }

    override fun toString(): String {
        return "LinuxModule{" +
                "base=0x" + java.lang.Long.toHexString(base) +
                ", size=" + size +
                ", name='" + name + '\'' +
                '}'
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(LinuxModule::class.java)

        @JvmStatic
        fun createVirtualModule(name: String, symbols: Map<String, VortexdbgPointer>, emulator: Emulator<*>): LinuxModule {
            if (symbols.isEmpty()) {
                throw IllegalArgumentException("symbols is empty")
            }

            val list = ArrayList(symbols.values)
            list.sortWith(Comparator { o1, o2 -> (o1.peer - o2.peer).toInt() })
            val first = list[0]
            val last = list[list.size - 1]
            val alignment = ARM.align(first.peer, last.peer - first.peer, emulator.getPageAlign().toLong())
            val base = alignment.address
            val size = alignment.size

            if (log.isDebugEnabled) {
                log.debug("createVirtualModule first=0x{}, last=0x{}, base=0x{}, size=0x{}", java.lang.Long.toHexString(first.peer), java.lang.Long.toHexString(last.peer), java.lang.Long.toHexString(base), java.lang.Long.toHexString(size))
            }

            val module: LinuxModule = object : LinuxModule(base, base, size, name, null,
                    ArrayList(), ArrayList(),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, null, null, null, null,
                    null) {
                override fun findSymbolByName(name: String, withDependencies: Boolean): Symbol? {
                    val pointer = symbols[name]
                    return if (pointer != null) {
                        VirtualSymbol(name, this, pointer.peer)
                    } else {
                        null
                    }
                }
                override fun getELFSymbolByName(name: String): ElfSymbol? {
                    return null
                }
                override fun isVirtual(): Boolean {
                    return true
                }
            }
            for (entry in symbols.entries) {
                module.registerSymbol(entry.key, entry.value.peer)
            }
            return module
        }
    }
}
