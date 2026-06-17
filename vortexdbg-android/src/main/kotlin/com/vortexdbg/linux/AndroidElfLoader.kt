package com.vortexdbg.linux

import com.vortexdbg.Alignment
import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Symbol
import com.vortexdbg.arm.ARM
import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.file.FileIO
import com.vortexdbg.file.linux.AndroidFileIO
import com.vortexdbg.file.linux.IOConstants
import com.vortexdbg.hook.HookListener
import com.vortexdbg.linux.android.ElfLibraryFile
import com.vortexdbg.linux.thread.PThreadInternal
import com.vortexdbg.memory.MemRegion
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.MemoryAllocBlock
import com.vortexdbg.memory.MemoryBlock
import com.vortexdbg.memory.MemoryBlockImpl
import com.vortexdbg.memory.MemoryMap
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.spi.AbstractLoader
import com.vortexdbg.spi.InitFunction
import com.vortexdbg.spi.InitFunctionFilter
import com.vortexdbg.spi.LibraryFile
import com.vortexdbg.LibraryResolver
import com.vortexdbg.spi.Loader
import com.vortexdbg.thread.Task
import com.vortexdbg.unix.IO
import com.vortexdbg.unix.UnixSyscallHandler
import com.vortexdbg.virtualmodule.VirtualSymbol
import com.sun.jna.Pointer
import net.fornwall.jelf.ArmExIdx
import net.fornwall.jelf.ElfDynamicStructure
import net.fornwall.jelf.ElfException
import net.fornwall.jelf.ElfFile
import net.fornwall.jelf.ElfRelocation
import net.fornwall.jelf.ElfSection
import net.fornwall.jelf.ElfSegment
import net.fornwall.jelf.ElfSymbol
import net.fornwall.jelf.GnuEhFrameHeader
import net.fornwall.jelf.MemoizedObject
import net.fornwall.jelf.PtLoadData
import net.fornwall.jelf.SymbolLocator
import org.apache.commons.io.FilenameUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.ArmConst
import unicorn.Unicorn
import unicorn.UnicornConst

import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap

open class AndroidElfLoader(emulator: Emulator<AndroidFileIO>, syscallHandler: UnixSyscallHandler<AndroidFileIO>) :
    AbstractLoader<AndroidFileIO>(emulator, syscallHandler), Memory, Loader {

    private var malloc: Symbol? = null
    private var free: Symbol? = null

    private val environ: VortexdbgPointer

    private lateinit var errno: Pointer

    init {
        // init stack
        stackSize = Memory.STACK_SIZE_OF_PAGE * emulator.getPageAlign()
        backend.mem_map(Memory.STACK_BASE - stackSize, stackSize.toLong(), UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_WRITE)

        setStackPoint(Memory.STACK_BASE)
        this.environ = initializeTLS(arrayOf(
                "ANDROID_DATA=/data",
                "ANDROID_ROOT=/system",
                "PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin",
                "NO_ADDR_COMPAT_LAYOUT_FIXUP=1"
        ))
        this.setErrno(0)
    }

    override fun setLibraryResolver(libraryResolver: LibraryResolver) {
        super.setLibraryResolver(libraryResolver)

        /*
         * 注意打开顺序很重要
         */
        syscallHandler.open(emulator, IO.STDIN, IOConstants.O_RDONLY)
        syscallHandler.open(emulator, IO.STDOUT, IOConstants.O_WRONLY)
        syscallHandler.open(emulator, IO.STDERR, IOConstants.O_WRONLY)
    }

    override fun createLibraryFile(file: File): LibraryFile {
        return ElfLibraryFile(file, emulator.is64Bit())
    }

    private fun initializeTLS(envs: Array<String>): VortexdbgPointer {
        val thread = allocateStack(0x400) // reserve space for pthread_internal_t
        val pThread = PThreadInternal.create(emulator, thread)
        pThread.tid = emulator.getPid()
        pThread.pack()

        val __stack_chk_guard = allocateStack(emulator.getPointerSize())

        val programName = writeStackString(emulator.getProcessName())

        val programNamePointer = allocateStack(emulator.getPointerSize())
        assert(programNamePointer != null)
        programNamePointer.setPointer(0L, programName)

        val auxv = allocateStack(0x100)
        assert(auxv != null)
        val AT_RANDOM = 25 // AT_RANDOM is a pointer to 16 bytes of randomness on the stack.
        auxv.setPointer(0L, VortexdbgPointer.pointer(emulator, AT_RANDOM.toLong()))
        auxv.setPointer(emulator.getPointerSize().toLong(), __stack_chk_guard)
        val AT_PAGESZ = 6
        auxv.setPointer(emulator.getPointerSize() * 2L, VortexdbgPointer.pointer(emulator, AT_PAGESZ.toLong()))
        auxv.setPointer(emulator.getPointerSize() * 3L, VortexdbgPointer.pointer(emulator, ARMEmulator.PAGE_ALIGN.toLong()))

        val envList = ArrayList<String>()
        for (env in envs) {
            val index = env.indexOf('=')
            if (index != -1) {
                envList.add(env)
            }
        }
        val environ = allocateStack(emulator.getPointerSize() * (envList.size + 1))
        assert(environ != null)
        var pointer: Pointer = environ
        for (env in envList) {
            val envPointer = writeStackString(env)
            pointer.setPointer(0L, envPointer)
            pointer = pointer.share(emulator.getPointerSize().toLong())
        }
        pointer.setPointer(0L, null)

        val argv = allocateStack(0x100)
        assert(argv != null)
        argv.setPointer(emulator.getPointerSize().toLong(), programNamePointer)
        argv.setPointer(2L * emulator.getPointerSize(), environ)
        argv.setPointer(3L * emulator.getPointerSize(), auxv)

        val tls = allocateStack(0x80 * 4) // tls size
        assert(tls != null)
        tls.setPointer(emulator.getPointerSize().toLong(), thread)
        this.errno = tls.share(emulator.getPointerSize() * 2L)
        tls.setPointer(emulator.getPointerSize() * 3L, argv)

        if (emulator.is32Bit()) {
            backend.reg_write(ArmConst.UC_ARM_REG_C13_C0_3, tls.peer)
        } else {
            backend.reg_write(Arm64Const.UC_ARM64_REG_TPIDR_EL0, tls.peer)
        }

        var sp = getStackPoint()
        sp = sp and (if (emulator.is64Bit()) 15 else 7).toLong().inv()
        setStackPoint(sp)

        if (log.isDebugEnabled) {
            log.debug("initializeTLS tls={}, argv={}, auxv={}, thread={}, environ={}, sp=0x{}", tls, argv, auxv, thread, environ, java.lang.Long.toHexString(getStackPoint()))
        }
        return argv.share(2L * emulator.getPointerSize(), 0L)
    }

    private val modules: MutableMap<String, LinuxModule> = LinkedHashMap()

    override fun loadInternal(libraryFile: LibraryFile, forceCallInit: Boolean): LinuxModule {
        try {
            val module = loadInternal(libraryFile)
            resolveSymbols(!forceCallInit)
            if (callInitFunction || forceCallInit) {
                for (m in modules.values.toTypedArray()) {
                    val forceCall = (forceCallInit && m === module) || m.isForceCallInit()
                    if (callInitFunction) {
                        m.callInitFunction(emulator, forceCall)
                    } else if (forceCall) {
                        m.callInitFunction(emulator, true)
                    }
                    m.initFunctionList.clear()
                }
            }
            module.addReferenceCount()
            return module
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    @Throws(IOException::class)
    private fun resolveSymbols(showWarning: Boolean) {
        val linuxModules = modules.values
        for (m in linuxModules) {
            val iterator = m.getUnresolvedSymbol().iterator()
            while (iterator.hasNext()) {
                val moduleSymbol = iterator.next()
                val resolved = moduleSymbol.resolve(HashSet<Module>(linuxModules), true, hookListeners, emulator.getSvcMemory())
                if (resolved != null) {
                    log.debug("resolveSymbols[{}]{} symbol resolved to {}", moduleSymbol.soName, moduleSymbol.symbol!!.getName(), resolved.toSoName)
                    resolved.relocation(emulator, m)
                    iterator.remove()
                } else if (showWarning) {
                    log.info("[{}]symbol {} is missing relocationAddr={}, offset=0x{}", moduleSymbol.soName, moduleSymbol.symbol, moduleSymbol.relocationAddr, java.lang.Long.toHexString(moduleSymbol.offset))
                }
            }
        }
    }

    override fun dlopen(filename: String, calInit: Boolean): Module? {
        val loaded = modules[FilenameUtils.getName(filename)]
        if (loaded != null) {
            loaded.addReferenceCount()
            return loaded
        }

        for (module in getLoadedModules()) {
            for (memRegion in module.getRegions()) {
                if (filename == memRegion.getName()) {
                    module.addReferenceCount()
                    return module
                }
            }
        }

        val file = if (libraryResolver == null) null else libraryResolver!!.resolveLibrary(emulator, filename)
        if (file == null) {
            return null
        }

        if (calInit) {
            return loadInternal(file, false)
        }

        try {
            val module = loadInternal(file)
            resolveSymbols(false)
            if (!callInitFunction) { // No need call init array
                for (m in modules.values) {
                    m.initFunctionList.clear()
                }
            }
            module.addReferenceCount()
            return module
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    /**
     * dlopen调用init_array会崩溃
     */
    override fun dlopen(filename: String): Module? {
        return dlopen(filename, true)
    }

    override fun dlsym(handle: Long, symbolName: String): Symbol? {
        if ("environ" == symbolName) {
            return VirtualSymbol(symbolName, null, environ.toUIntPeer())
        }
        var sm: Module? = null
        var ret: Symbol? = null
        for (module in modules.values) {
            if (module.base == handle) { // virtual module may have same base address
                val symbol = module.findSymbolByName(symbolName, false)
                if (symbol != null) {
                    ret = symbol
                    sm = module
                    break
                }
            }
        }
        if (ret == null && (handle.toInt() == RTLD_DEFAULT || handle == 0L)) {
            for (module in modules.values) {
                val symbol = module.findSymbolByName(symbolName, false)
                if (symbol != null) {
                    ret = symbol
                    sm = module
                    break
                }
            }
        }
        for (listener in hookListeners) {
            val hook = listener.hook(emulator.getSvcMemory(), if (sm == null) "" else sm.name, symbolName, if (ret == null) 0L else ret.getAddress())
            if (hook != 0L) {
                return VirtualSymbol(symbolName, null, hook)
            }
        }
        return ret
    }

    override fun dlclose(handle: Long): Boolean {
        val iterator = modules.entries.iterator()
        while (iterator.hasNext()) {
            val module = iterator.next().value
            if (module.base == handle) {
                if (module.decrementReferenceCount() <= 0) {
                    module.unload(backend)
                    iterator.remove()
                }
                return true
            }
        }
        return false
    }

    @Throws(IOException::class)
    private fun loadInternal(libraryFile: LibraryFile): LinuxModule {
        val elfFile = ElfFile.fromBuffer(libraryFile.mapBuffer())

        if (emulator.is32Bit() && elfFile.objectSize != ElfFile.CLASS_32) {
            throw ElfException("Must be 32-bit")
        }
        if (emulator.is64Bit() && elfFile.objectSize != ElfFile.CLASS_64) {
            throw ElfException("Must be 64-bit")
        }

        if (elfFile.encoding != ElfFile.DATA_LSB) {
            throw ElfException("Must be LSB")
        }

        if (emulator.is32Bit() && elfFile.arch.toInt() != ElfFile.ARCH_ARM) {
            throw ElfException("Must be ARM arch.")
        }

        if (emulator.is64Bit() && elfFile.arch.toInt() != ElfFile.ARCH_AARCH64) {
            throw ElfException("Must be ARM64 arch.")
        }

        val start = System.currentTimeMillis()
        var bound_high: Long = 0
        var align: Long = 0
        for (i in 0 until elfFile.num_ph.toInt()) {
            val ph = elfFile.getProgramHeader(i)
            if (ph.type == ElfSegment.PT_LOAD && ph.mem_size > 0) {
                val high = ph.virtual_address + ph.mem_size

                if (bound_high < high) {
                    bound_high = high
                }
                if (ph.alignment > align) {
                    align = ph.alignment
                }
            }
        }

        var dynamicStructure: ElfDynamicStructure? = null

        val baseAlign = Math.max(emulator.getPageAlign().toLong(), align)
        val load_base = ((mmapBaseAddress - 1) / baseAlign + 1) * baseAlign
        var load_virtual_address: Long = 0
        val size = ARM.align(0, bound_high, baseAlign).size
        setMMapBaseAddress(load_base + size)

        val regions = ArrayList<MemRegion>(5)
        var armExIdx: MemoizedObject<ArmExIdx>? = null
        var ehFrameHeader: MemoizedObject<GnuEhFrameHeader>? = null
        var lastAlignment: Alignment? = null
        for (i in 0 until elfFile.num_ph.toInt()) {
            val ph = elfFile.getProgramHeader(i)
            when (ph.type) {
                ElfSegment.PT_LOAD -> {
                    var prot = get_segment_protection(ph.flags)
                    if (prot == UnicornConst.UC_PROT_NONE) {
                        prot = UnicornConst.UC_PROT_ALL
                    }

                    val begin = load_base + ph.virtual_address
                    if (load_virtual_address == 0L) {
                        load_virtual_address = begin
                    }

                    val check = ARM.align(begin, ph.mem_size, Math.max(emulator.getPageAlign().toLong(), ph.alignment))
                    val regionSize = regions.size
                    val last = if (regionSize == 0) null else regions[regionSize - 1]
                    var overall: MemRegion? = null
                    if (last != null && check.address >= last.begin && check.address < last.end) {
                        overall = last
                    }
                    if (overall != null) {
                        val overallSize = overall.end - check.address
                        var perms = overall.perms or prot
                        if (mMapListener != null) {
                            perms = mMapListener!!.onProtect(check.address, overallSize, perms)
                        }
                        backend.mem_protect(check.address, overallSize, perms)
                        if (ph.mem_size > overallSize) {
                            val alignment = this.mem_map(begin + overallSize, ph.mem_size - overallSize, prot, libraryFile.getName(), Math.max(emulator.getPageAlign().toLong(), ph.alignment))
                            regions.add(MemRegion(begin, alignment.address, alignment.address + alignment.size, prot, libraryFile, ph.virtual_address))
                            if (lastAlignment != null && lastAlignment.begin + lastAlignment.dataSize > begin) {
                                throw UnsupportedOperationException()
                            }
                            lastAlignment = alignment
                            lastAlignment.begin = begin
                        }
                    } else {
                        val alignment = this.mem_map(begin, ph.mem_size, prot, libraryFile.getName(), Math.max(emulator.getPageAlign().toLong(), ph.alignment))
                        regions.add(MemRegion(begin, alignment.address, alignment.address + alignment.size, prot, libraryFile, ph.virtual_address))
                        if (lastAlignment != null) {
                            val base = lastAlignment.address + lastAlignment.size
                            val off = alignment.address - base
                            if (off < 0) {
                                throw IllegalStateException()
                            }
                            if (off > 0) {
                                backend.mem_map(base, off, UnicornConst.UC_PROT_NONE)
                                if (mMapListener != null) {
                                    mMapListener!!.onMap(base, off, UnicornConst.UC_PROT_NONE)
                                }
                                if (memoryMap.put(base, MemoryMap(base, off, UnicornConst.UC_PROT_NONE)) != null) {
                                    log.warn("mem_map replace exists memory map base={}", java.lang.Long.toHexString(base))
                                }
                            }
                        }
                        lastAlignment = alignment
                        lastAlignment.begin = begin
                    }

                    val loadData = ph.getPtLoadData()!!
                    loadData.writeTo(pointer(begin))
                    if (lastAlignment != null) {
                        lastAlignment.dataSize = loadData.getDataSize()
                    }
                }
                ElfSegment.PT_DYNAMIC -> {
                    dynamicStructure = ph.getDynamicStructure()
                }
                ElfSegment.PT_INTERP -> {
                    if (log.isDebugEnabled) {
                        log.debug("[{}]interp={}", libraryFile.getName(), ph.getInterpreter())
                    }
                }
                ElfSegment.PT_GNU_EH_FRAME -> {
                    @Suppress("UNCHECKED_CAST")
                    ehFrameHeader = ph.getEhFrameHeader() as MemoizedObject<GnuEhFrameHeader>?
                }
                ElfSegment.PT_ARM_EXIDX -> {
                    armExIdx = ph.getARMExIdxData()
                }
                else -> {
                    if (log.isDebugEnabled) {
                        log.debug("[{}]segment type=0x{}, offset=0x{}", libraryFile.getName(), Integer.toHexString(ph.type), java.lang.Long.toHexString(ph.offset))
                    }
                }
            }
        }

        if (dynamicStructure == null) {
            throw IllegalStateException("dynamicStructure is empty.")
        }
        val soName = dynamicStructure.getSOName(libraryFile.getName())

        val neededLibraries = HashMap<String, Module>()
        for (neededLibrary in dynamicStructure.getNeededLibraries()) {
            if (log.isDebugEnabled) {
                log.debug("{} need dependency {}", soName, neededLibrary)
            }

            val loaded = modules[neededLibrary]
            if (loaded != null) {
                loaded.addReferenceCount()
                neededLibraries[FilenameUtils.getBaseName(loaded.name)] = loaded
                continue
            }
            var neededLibraryFile = libraryFile.resolveLibrary(emulator, neededLibrary)
            if (libraryResolver != null && neededLibraryFile == null) {
                neededLibraryFile = libraryResolver!!.resolveLibrary(emulator, neededLibrary)
            }
            if (neededLibraryFile != null) {
                val needed = loadInternal(neededLibraryFile)
                needed.addReferenceCount()
                neededLibraries[FilenameUtils.getBaseName(needed.name)] = needed
            } else {
                log.info("{} load dependency {} failed", soName, neededLibrary)
            }
        }

        for (module in modules.values) {
            val iterator = module.getUnresolvedSymbol().iterator()
            while (iterator.hasNext()) {
                val moduleSymbol = iterator.next()
                val resolved = moduleSymbol.resolve(module.getNeededLibraries(), false, hookListeners, emulator.getSvcMemory())
                if (resolved != null) {
                    if (log.isDebugEnabled) {
                        log.debug("[{}]{} symbol resolved to {}", moduleSymbol.soName, moduleSymbol.symbol!!.getName(), resolved.toSoName)
                    }
                    resolved.relocation(emulator, module)
                    iterator.remove()
                }
            }
        }

        val list = ArrayList<ModuleSymbol>()
        val resolvedSymbols = ArrayList<ModuleSymbol>()
        for (`object` in dynamicStructure.getRelocations()) {
            val relocation = `object`.getValue()
            val type = relocation.type()
            if (type == 0) {
                log.warn("Unhandled relocation type {}", type)
                continue
            }
            val symbol = if (relocation.sym() == 0) null else relocation.symbol()
            val sym_value = if (symbol != null) symbol.value else 0
            val relocationAddr: Pointer = VortexdbgPointer.pointer(emulator, load_base + relocation.offset())
            assert(relocationAddr != null)

            val log = LoggerFactory.getLogger("com.vortexdbg.linux.$soName")
            if (log.isDebugEnabled) {
                log.debug("symbol={}, type={}, relocationAddr={}, offset=0x{}, addend={}, sym={}, android={}", symbol, type, relocationAddr, java.lang.Long.toHexString(relocation.offset()), relocation.addend(), relocation.sym(), relocation.isAndroid())
            }

            val moduleSymbol: ModuleSymbol?
            when (type) {
                ARMEmulator.R_ARM_ABS32 -> {
                    val offset = relocationAddr.getInt(0L).toLong()
                    moduleSymbol = resolveSymbol(load_base, symbol, relocationAddr, soName, neededLibraries.values, offset)
                    if (moduleSymbol == null) {
                        list.add(ModuleSymbol(soName, load_base, symbol, relocationAddr, null, offset))
                    } else {
                        resolvedSymbols.add(moduleSymbol)
                    }
                }
                ARMEmulator.R_AARCH64_ABS64 -> {
                    val offset = relocationAddr.getLong(0L) + relocation.addend()
                    moduleSymbol = resolveSymbol(load_base, symbol, relocationAddr, soName, neededLibraries.values, offset)
                    if (moduleSymbol == null) {
                        list.add(ModuleSymbol(soName, load_base, symbol, relocationAddr, null, offset))
                    } else {
                        resolvedSymbols.add(moduleSymbol)
                    }
                }
                ARMEmulator.R_ARM_RELATIVE -> {
                    val offset = relocationAddr.getInt(0L)
                    if (sym_value == 0L) {
                        relocationAddr.setInt(0L, load_base.toInt() + offset)
                    } else {
                        throw IllegalStateException("sym_value=0x" + java.lang.Long.toHexString(sym_value))
                    }
                }
                ARMEmulator.R_AARCH64_RELATIVE -> {
                    if (sym_value == 0L) {
                        relocationAddr.setLong(0L, load_base + relocation.addend())
                    } else {
                        throw IllegalStateException("sym_value=0x" + java.lang.Long.toHexString(sym_value))
                    }
                }
                ARMEmulator.R_ARM_GLOB_DAT, ARMEmulator.R_ARM_JUMP_SLOT -> {
                    moduleSymbol = resolveSymbol(load_base, symbol, relocationAddr, soName, neededLibraries.values, 0)
                    if (moduleSymbol == null) {
                        list.add(ModuleSymbol(soName, load_base, symbol, relocationAddr, null, 0))
                    } else {
                        resolvedSymbols.add(moduleSymbol)
                    }
                }
                ARMEmulator.R_AARCH64_GLOB_DAT, ARMEmulator.R_AARCH64_JUMP_SLOT -> {
                    moduleSymbol = resolveSymbol(load_base, symbol, relocationAddr, soName, neededLibraries.values, relocation.addend())
                    if (moduleSymbol == null) {
                        list.add(ModuleSymbol(soName, load_base, symbol, relocationAddr, null, relocation.addend()))
                    } else {
                        resolvedSymbols.add(moduleSymbol)
                    }
                }
                ARMEmulator.R_ARM_COPY -> throw IllegalStateException("R_ARM_COPY relocations are not supported")
                ARMEmulator.R_AARCH64_COPY -> throw IllegalStateException("R_AARCH64_COPY relocations are not supported")
                ARMEmulator.R_AARCH64_ABS32,
                ARMEmulator.R_AARCH64_ABS16,
                ARMEmulator.R_AARCH64_PREL64,
                ARMEmulator.R_AARCH64_PREL32,
                ARMEmulator.R_AARCH64_PREL16,
                ARMEmulator.R_AARCH64_IRELATIVE,
                ARMEmulator.R_AARCH64_TLS_TPREL64,
                ARMEmulator.R_AARCH64_TLS_DTPREL32,
                ARMEmulator.R_ARM_IRELATIVE,
                ARMEmulator.R_ARM_REL32 -> {
                    log.warn("[{}]Unhandled relocation type {}, symbol={}, relocationAddr={}, offset=0x{}, addend={}, android={}", soName, type, symbol, relocationAddr, java.lang.Long.toHexString(relocation.offset()), relocation.addend(), relocation.isAndroid())
                }
                else -> {
                    log.warn("[{}]Unhandled relocation type {}, symbol={}, relocationAddr={}, offset=0x{}, addend={}, android={}", soName, type, symbol, relocationAddr, java.lang.Long.toHexString(relocation.offset()), relocation.addend(), relocation.isAndroid())
                }
            }
        }

        val initFunctionList = ArrayList<InitFunction>()
        val preInitArraySize = dynamicStructure.getPreInitArraySize()
        val executable = elfFile.file_type.toInt() == ElfFile.FT_EXEC || preInitArraySize > 0
        if (executable) {
            val count = preInitArraySize / emulator.getPointerSize()
            if (count > 0) {
                val pointer = VortexdbgPointer.pointer(emulator, load_base + dynamicStructure.getPreInitArrayOffset())
                    ?: throw IllegalStateException("DT_PREINIT_ARRAY is null")
                for (i in 0 until count) {
                    val ptr = pointer.share(i.toLong() * emulator.getPointerSize(), 0L)
                    initFunctionList.add(AbsoluteInitFunction(load_base, soName, ptr))
                }
            }
        }
        if (elfFile.file_type.toInt() == ElfFile.FT_DYN) { // not executable
            val init = dynamicStructure.getInit()
            if (init != 0) {
                initFunctionList.add(LinuxInitFunction(load_base, soName, init.toLong()))
            }

            val initArraySize = dynamicStructure.getInitArraySize()
            val count = initArraySize / emulator.getPointerSize()
            if (count > 0) {
                val pointer = VortexdbgPointer.pointer(emulator, load_base + dynamicStructure.getInitArrayOffset())
                    ?: throw IllegalStateException("DT_INIT_ARRAY is null")
                for (i in 0 until count) {
                    val ptr = pointer.share(i.toLong() * emulator.getPointerSize(), 0L)
                    initFunctionList.add(AbsoluteInitFunction(load_base, soName, ptr))
                }
            }
        }

        val dynsym: SymbolLocator = dynamicStructure.getSymbolStructure()
        var symbolTableSection: ElfSection? = null
        try {
            symbolTableSection = elfFile.getSymbolTableSection()
        } catch (ignored: Throwable) {
        }
        if (load_virtual_address == 0L) {
            throw IllegalStateException("load_virtual_address")
        }
        var initFunctionFilter: InitFunctionFilter? = null
        if (libraryResolver is InitFunctionFilter) {
            initFunctionFilter = libraryResolver as InitFunctionFilter
        }
        val module = LinuxModule(load_virtual_address, load_base, size, soName, dynsym, list, initFunctionList, neededLibraries, regions,
                armExIdx, ehFrameHeader, symbolTableSection, elfFile, dynamicStructure, libraryFile, initFunctionFilter)
        for (symbol in resolvedSymbols) {
            symbol.relocation(emulator, module)
        }
        if (executable) {
            for (linuxModule in modules.values) {
                for (entry in linuxModule.resolvedSymbols.entries) {
                    val symbol = module.getELFSymbolByName(entry.key)
                    if (symbol != null && !symbol.isUndef()) {
                        entry.value.relocation(emulator, module, symbol)
                    }
                }
                linuxModule.resolvedSymbols.clear()
            }
        }
        if ("libc.so" == soName) { // libc
            malloc = module.findSymbolByName("malloc", false)
            free = module.findSymbolByName("free", false)
        }

        modules[soName] = module
        if (maxSoName == null || soName.length > maxSoName!!.length) {
            maxSoName = soName
        }
        if (bound_high > maxSizeOfSo) {
            maxSizeOfSo = bound_high
        }
        module.setEntryPoint(elfFile.entry_point)
        log.debug("Load library {} offset={}ms, entry_point=0x{}", soName, System.currentTimeMillis() - start, java.lang.Long.toHexString(elfFile.entry_point))
        notifyModuleLoaded(module)
        return module
    }

    @JvmSuppressWildcards
    override fun loadVirtualModule(name: String, symbols: Map<String, VortexdbgPointer>): Module {
        val module = LinuxModule.createVirtualModule(name, symbols, emulator)
        modules[name] = module
        if (maxSoName == null || name.length > maxSoName!!.length) {
            maxSoName = name
        }
        return module
    }

    private var maxSoName: String? = null
    private var maxSizeOfSo: Long = 0

    @Throws(IOException::class)
    private fun resolveSymbol(load_base: Long, symbol: ElfSymbol?, relocationAddr: Pointer, soName: String, neededLibraries: Collection<Module>, offset: Long): ModuleSymbol? {
        if (symbol == null) {
            return ModuleSymbol(soName, load_base, null, relocationAddr, soName, offset)
        }

        if (!symbol.isUndef()) {
            for (listener in hookListeners) {
                val hook = listener.hook(emulator.getSvcMemory(), soName, symbol.getName()!!, load_base + symbol.value + offset)
                if (hook > 0) {
                    return ModuleSymbol(soName, ModuleSymbol.WEAK_BASE, symbol, relocationAddr, soName, hook)
                }
            }
            return ModuleSymbol(soName, load_base, symbol, relocationAddr, soName, offset)
        }

        return ModuleSymbol(soName, load_base, symbol, relocationAddr, null, offset).resolve(neededLibraries, false, hookListeners, emulator.getSvcMemory())
    }

    private fun get_segment_protection(flags: Int): Int {
        var prot = Unicorn.UC_PROT_NONE
        if ((flags and ElfSegment.PF_R) != 0) prot = prot or Unicorn.UC_PROT_READ
        if ((flags and ElfSegment.PF_W) != 0) prot = prot or Unicorn.UC_PROT_WRITE
        if ((flags and ElfSegment.PF_X) != 0) prot = prot or Unicorn.UC_PROT_EXEC
        return prot
    }

    override fun malloc(length: Int, runtime: Boolean): MemoryBlock {
        if (runtime) {
            return MemoryBlockImpl.alloc(this, length)
        } else {
            return MemoryAllocBlock.malloc(emulator, malloc!!, free!!, length)
        }
    }

    private var brk: Long = 0

    override fun brk(address: Long): Int {
        if (address == 0L) {
            this.brk = HEAP_BASE.toLong()
            return this.brk.toInt()
        }

        if (address % emulator.getPageAlign() != 0L) {
            throw UnsupportedOperationException()
        }

        if (address > brk) {
            backend.mem_map(brk, address - brk, UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_WRITE)
            if (mMapListener != null) {
                mMapListener!!.onMap(brk, address - brk, UnicornConst.UC_PROT_READ or UnicornConst.UC_PROT_WRITE)
            }
            this.brk = address
        } else if (address < brk) {
            backend.mem_unmap(address, brk - address)
            if (mMapListener != null) {
                mMapListener!!.onUnmap(address, brk - address)
            }
            this.brk = address
        }

        return this.brk.toInt()
    }

    override fun mmap2(start: Long, length: Int, prot: Int, flags: Int, fd: Int, offset: Int): Long {
        val aligned = ARM.alignSize(length.toLong(), emulator.getPageAlign().toLong()).toInt()
        if (log.isDebugEnabled) {
            log.debug("mmap2: start=0x{}, length=0x{}, prot=0x{}, fd={}, offset=0x{}", java.lang.Long.toHexString(start), Integer.toHexString(length), Integer.toHexString(prot), fd, Integer.toHexString(offset))
        }

        val isAnonymous = ((flags and MAP_ANONYMOUS) != 0) || (start == 0L && fd <= 0 && offset == 0)
        if ((flags and MAP_FIXED) != 0 && isAnonymous) {
            if (log.isDebugEnabled) {
                log.debug("mmap2 MAP_FIXED start=0x{}, length={}, prot={}", java.lang.Long.toHexString(start), length, prot)
            }

            var hasOverlap = false
            for (map in memoryMap.values) {
                if (start < map.base + map.size && start + aligned > map.base) {
                    hasOverlap = true
                    break
                }
            }
            if (hasOverlap) {
                munmap(start, length)
            } else if (log.isDebugEnabled) {
                log.debug("mmap2 MAP_FIXED no existing mapping at start=0x{}", java.lang.Long.toHexString(start))
            }
            backend.mem_map(start, aligned.toLong(), prot)
            if (mMapListener != null) {
                mMapListener!!.onMap(start, aligned.toLong(), prot)
            }
            if (memoryMap.put(start, MemoryMap(start, aligned.toLong(), prot)) != null) {
                log.warn("mmap2 replace exists memory map: start={}", java.lang.Long.toHexString(start))
            }
            return start
        }
        if (isAnonymous) {
            val addr = allocateMapAddress(0L, aligned.toLong())
            if (log.isDebugEnabled) {
                log.debug("mmap2 addr=0x{}, mmapBaseAddress=0x{}, start={}, fd={}, offset={}, aligned={}, LR={}", java.lang.Long.toHexString(addr), java.lang.Long.toHexString(mmapBaseAddress), start, fd, offset, aligned, emulator.getContext<RegisterContext>().getLRPointer())
            }
            backend.mem_map(addr, aligned.toLong(), prot)
            if (mMapListener != null) {
                mMapListener!!.onMap(addr, aligned.toLong(), prot)
            }
            if (memoryMap.put(addr, MemoryMap(addr, aligned.toLong(), prot)) != null) {
                log.warn("memoryMap mmap2 replace exists memory map addr={}", java.lang.Long.toHexString(addr))
            }
            return addr
        }
        try {
            if (start == 0L && fd > 0) {
                val file = syscallHandler.getFileIO(fd)
                if (file != null) {
                    val addr = allocateMapAddress(0L, aligned.toLong())
                    if (log.isDebugEnabled) {
                        log.debug("mmap2 addr=0x{}, mmapBaseAddress=0x{}", java.lang.Long.toHexString(addr), java.lang.Long.toHexString(mmapBaseAddress))
                    }
                    val ret = file.mmap2(emulator, addr, aligned, prot, offset, length)
                    if (mMapListener != null) {
                        mMapListener!!.onMap(addr, aligned.toLong(), prot)
                    }
                    if (memoryMap.put(addr, MemoryMap(addr, aligned.toLong(), prot)) != null) {
                        log.warn("mmap2 replace exists memory map addr=0x{}", java.lang.Long.toHexString(addr))
                    }
                    return ret
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
        try {
            if (fd > 0) {
                val file = syscallHandler.getFileIO(fd)
                if (file != null) {
                    if ((start and (emulator.getPageAlign() - 1).toLong()) != 0L) {
                        if (log.isDebugEnabled) {
                            log.warn("mmap2 start=0x{}, start=0x{}, flags=0x{}, length=0x{}", java.lang.Long.toHexString(start), java.lang.Long.toHexString(start), Integer.toHexString(flags), Integer.toHexString(length))
                        }
                        return MAP_FAILED.toLong()
                    }
                    val end = start + length
                    for (entry in memoryMap.entries) {
                        val map = entry.value
                        if (Math.max(start, entry.key) <= Math.min(map.base + map.size, end)) {
                            if (log.isDebugEnabled) {
                                log.warn("mmap2 start=0x{}, entry={}, flags=0x{}, length=0x{}", java.lang.Long.toHexString(start), entry, Integer.toHexString(flags), Integer.toHexString(length))
                            }
                            return MAP_FAILED.toLong()
                        }
                    }
                    if (log.isDebugEnabled) {
                        log.debug("mmap2 start=0x{}, mmapBaseAddress=0x{}, flags=0x{}, length=0x{}", java.lang.Long.toHexString(start), java.lang.Long.toHexString(mmapBaseAddress), Integer.toHexString(flags), Integer.toHexString(length))
                    }
                    val ret = file.mmap2(emulator, start, aligned, prot, offset, length)
                    if (mMapListener != null) {
                        mMapListener!!.onMap(start, aligned.toLong(), prot)
                    }
                    if (memoryMap.put(start, MemoryMap(start, aligned.toLong(), prot)) != null) {
                        log.warn("mmap2 replace exists memory map start=0x{}", java.lang.Long.toHexString(start))
                    }
                    return ret
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }

        emulator.attach().debug("mmap2 failed: start=0x" + java.lang.Long.toHexString(start) + ", length=" + length + ", prot=0x" + Integer.toHexString(prot) + ", flags=0x" + Integer.toHexString(flags) + ", fd=" + fd + ", offset=" + offset)
        throw AbstractMethodError("mmap2 start=0x" + java.lang.Long.toHexString(start) + ", length=" + length + ", prot=0x" + Integer.toHexString(prot) + ", flags=0x" + Integer.toHexString(flags) + ", fd=" + fd + ", offset=" + offset)
    }

    private var lastErrno: Int = 0

    override fun getLastErrno(): Int {
        return lastErrno
    }

    override fun setErrno(errno: Int) {
        this.lastErrno = errno
        val task = emulator.get<Task>(Task.TASK_KEY)
        if (task != null && task.setErrno(emulator, errno)) {
            return
        }
        this.errno.setInt(0L, errno)
    }

    override fun getMaxLengthLibraryName(): String {
        return maxSoName!!
    }

    override fun getMaxSizeOfLibrary(): Long {
        return maxSizeOfSo
    }

    override fun getLoadedModules(): Collection<Module> {
        return ArrayList<Module>(modules.values)
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AndroidElfLoader::class.java)

        private const val RTLD_DEFAULT = -1

        private const val HEAP_BASE = 0x8048000

        private const val MAP_FAILED = -1
        const val MAP_FIXED = 0x10
        const val MAP_ANONYMOUS = 0x20
    }
}
