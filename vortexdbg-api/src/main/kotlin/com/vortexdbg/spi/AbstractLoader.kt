package com.vortexdbg.spi

import com.vortexdbg.Alignment
import com.vortexdbg.Emulator
import com.vortexdbg.LibraryResolver
import com.vortexdbg.Module
import com.vortexdbg.ModuleListener
import com.vortexdbg.arm.ARM
import com.vortexdbg.arm.ARMEmulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.file.NewFileIO
import com.vortexdbg.hook.HookListener
import com.vortexdbg.memory.MMapListener
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.MemoryMap
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.thread.BaseTask
import com.vortexdbg.unix.UnixEmulator
import com.vortexdbg.unix.UnixSyscallHandler
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.ArmConst

import java.io.DataOutput
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayList
import java.util.Arrays
import java.util.TreeMap

abstract class AbstractLoader<T : NewFileIO>(
    @JvmField protected val emulator: Emulator<T>,
    @JvmField protected val syscallHandler: UnixSyscallHandler<T>
) : Memory, Loader {

    @JvmField
    protected val backend: Backend = emulator.getBackend()

    @JvmField
    protected var sp: Long = 0
    @JvmField
    protected var mmapBaseAddress: Long = 0
    @JvmField
    protected val memoryMap: MutableMap<Long, MemoryMap> = TreeMap()

    @JvmField
    protected var threadStackMap = arrayOfNulls<Boolean>(Memory.MAX_THREADS)

    @JvmField
    protected var mMapListener: MMapListener? = null

    init {
        setMMapBaseAddress(Memory.MMAP_BASE)
    }

    override fun allocateThreadIndex(): Int {
        for (i in threadStackMap.indices) {
            if (threadStackMap[i] == null || !threadStackMap[i]!!) {
                threadStackMap[i] = true
                return i
            }
        }
        throw UnsupportedOperationException("Threads is too much, max is = " + threadStackMap.size)
    }

    override fun freeThreadIndex(index: Int) {
        if (index >= 0) {
            threadStackMap[index] = false
        }
    }

    override fun allocateThreadStack(index: Int): VortexdbgPointer {
        if (!threadStackMap[index]!!) {
            throw UnsupportedOperationException("Your ThreadStackIndex doesn't exist, it must come from allocateThreadIndex(), index = $index")
        }
        val threadStackBase = Memory.STACK_BASE - Memory.STACK_SIZE_OF_MAIN_PAGE.toLong() * emulator.getPageAlign()
        val address = threadStackBase - BaseTask.THREAD_STACK_PAGE.toLong() * index * emulator.getPageAlign()
        if (log.isDebugEnabled) {
            log.debug("allocateThreadStackAddress=0x{}", java.lang.Long.toHexString(address))
        }
        return VortexdbgPointer.pointer(emulator, address)
    }

    override fun setMMapListener(listener: MMapListener?) {
        this.mMapListener = listener
    }

    override fun getMMapListener(): MMapListener? {
        return mMapListener
    }

    protected fun setMMapBaseAddress(address: Long) {
        this.mmapBaseAddress = address

        if (log.isDebugEnabled) {
            log.debug("setMMapBaseAddress=0x{}", java.lang.Long.toHexString(address))
        }
    }

    override fun getMemoryMap(): Collection<MemoryMap> {
        return memoryMap.values
    }

    final override fun mmap(length: Int, prot: Int): VortexdbgPointer {
        val aligned = ARM.alignSize(length.toLong(), emulator.getPageAlign().toLong()).toInt()
        val addr = mmap2(0, aligned, prot, 0, -1, 0)
        val pointer = VortexdbgPointer.pointer(emulator, addr)
        assert(pointer != null)
        return pointer.setSize(aligned.toLong())
    }

    protected fun allocateMapAddress(mask: Long, length: Long): Long {
        var lastEntry: Map.Entry<Long, MemoryMap>? = null
        for (entry in memoryMap.entries) {
            if (lastEntry == null) {
                lastEntry = entry
            } else {
                val map = lastEntry.value
                val mmapAddress = map.base + map.size
                if (mmapAddress + length < entry.key && (mmapAddress and mask) == 0L) {
                    return mmapAddress
                } else {
                    lastEntry = entry
                }
            }
        }
        if (lastEntry != null) {
            val map = lastEntry.value
            val mmapAddress = map.base + map.size
            if (mmapAddress < mmapBaseAddress) {
                log.debug("allocateMapAddress mmapBaseAddress=0x{}, mmapAddress=0x{}", java.lang.Long.toHexString(mmapBaseAddress), java.lang.Long.toHexString(mmapAddress))
                setMMapBaseAddress(mmapAddress)
            }
        }

        var addr = mmapBaseAddress
        while ((addr and mask) != 0L) {
            addr += emulator.getPageAlign()
        }
        setMMapBaseAddress(addr + length)
        return addr
    }

    final override fun munmap(start: Long, length: Int): Int {
        val aligned = ARM.alignSize(length.toLong(), emulator.getPageAlign().toLong()).toInt()
        backend.mem_unmap(start, aligned.toLong())
        if (mMapListener != null) {
            mMapListener!!.onUnmap(start, aligned.toLong())
        }
        val removed = memoryMap.remove(start)

        if (removed == null) {
            val segment = findMemoryMap(start, aligned)
            if (start + aligned < segment.base + segment.size) {
                val newSize = segment.base + segment.size - start - aligned
                if (log.isDebugEnabled) {
                    log.debug("munmap aligned=0x{}, start=0x{}, base=0x{}, newSize={}", java.lang.Long.toHexString(aligned.toLong()), java.lang.Long.toHexString(start), java.lang.Long.toHexString(start + aligned), newSize)
                }
                if (memoryMap.put(start + aligned, MemoryMap(start + aligned, newSize, segment.prot)) != null) {
                    log.warn("munmap replace exists memory map addr=0x{}", java.lang.Long.toHexString(start + aligned))
                }
            }
            if (memoryMap.put(segment.base, MemoryMap(segment.base, start - segment.base, segment.prot)) == null) {
                log.warn("munmap replace failed warning: addr=0x{}", java.lang.Long.toHexString(segment.base))
            }
            if (log.isDebugEnabled) {
                log.debug("munmap aligned=0x{}, start=0x{}, base=0x{}, size={}", java.lang.Long.toHexString(aligned.toLong()), java.lang.Long.toHexString(start), java.lang.Long.toHexString(segment.base), start - segment.base)
            }
            return segment.prot
        }

        if (removed.size != aligned.toLong()) {
            if (aligned >= removed.size) {
                if (log.isDebugEnabled) {
                    log.debug("munmap removed=0x{}, aligned=0x{}, start=0x{}", java.lang.Long.toHexString(removed.size), java.lang.Long.toHexString(aligned.toLong()), java.lang.Long.toHexString(start))
                }
                var address = start + removed.size
                var size = aligned - removed.size
                while (size != 0L) {
                    val remove = memoryMap.remove(address)
                    if (remove == null) {
                        log.warn("munmap failed to find adjacent region at address=0x{}", java.lang.Long.toHexString(address))
                        break
                    }
                    if (removed.prot != remove.prot) {
                        log.warn("munmap prot mismatch: removed.prot={}, remove.prot={}, address=0x{}", removed.prot,
                                remove.prot, java.lang.Long.toHexString(address))
                    }
                    if (remove.size > size) {
                        throw IllegalStateException("munmap adjacent region size=0x" + java.lang.Long.toHexString(remove.size) + " exceeds remaining=0x" + java.lang.Long.toHexString(size) + " at address=0x" + java.lang.Long.toHexString(address))
                    }
                    address += remove.size
                    size -= remove.size
                }
                return removed.prot
            }

            if (memoryMap.put(start + aligned, MemoryMap(start + aligned, removed.size - aligned, removed.prot)) != null) {
                log.warn("munmap not aligned replace exists memory map addr=0x{}", java.lang.Long.toHexString(start + aligned))
            }
            if (log.isDebugEnabled) {
                log.debug("munmap removed=0x{}, aligned=0x{}, base=0x{}, size={}", java.lang.Long.toHexString(removed.size), java.lang.Long.toHexString(aligned.toLong()), java.lang.Long.toHexString(start + aligned), removed.size - aligned)
            }
            return removed.prot
        }

        if (log.isDebugEnabled) {
            log.debug("munmap aligned=0x{}, start=0x{}, base=0x{}, size={}", java.lang.Long.toHexString(aligned.toLong()), java.lang.Long.toHexString(start), java.lang.Long.toHexString(removed.base), removed.size)
        }
        if (memoryMap.isEmpty()) {
            setMMapBaseAddress(Memory.MMAP_BASE)
        }
        return removed.prot
    }

    private fun findMemoryMap(start: Long, aligned: Int): MemoryMap {
        var segment: MemoryMap? = null
        for (entry in memoryMap.entries) {
            val map = entry.value
            if (start > entry.key && start < map.base + map.size) {
                segment = entry.value
                break
            }
        }
        if (segment == null || start + aligned > segment.base + segment.size) {
            throw IllegalStateException("munmap aligned=0x" + java.lang.Long.toHexString(aligned.toLong()) + ", start=0x" + java.lang.Long.toHexString(start))
        }
        return segment
    }

    final override fun mprotect(address: Long, length: Int, prot: Int): Int {
        var prot = prot
        if (address % ARMEmulator.PAGE_ALIGN != 0L) {
            setErrno(UnixEmulator.EINVAL)
            return -1
        }

        val aligned = ARM.alignSize(length.toLong(), emulator.getPageAlign().toLong()).toInt()
        if (mMapListener != null) {
            prot = mMapListener!!.onProtect(address, aligned.toLong(), prot)
        }
        backend.mem_protect(address, aligned.toLong(), prot)

        val protEnd = address + aligned
        val affected = ArrayList<MemoryMap>()
        for (m in memoryMap.values) {
            if (address < m.base + m.size && protEnd > m.base) {
                affected.add(m)
            }
        }
        for (map in affected) {
            val mapEnd = map.base + map.size
            if (address <= map.base && protEnd >= mapEnd) {
                map.prot = prot
            } else if (address <= map.base) {
                memoryMap.remove(map.base)
                memoryMap.put(map.base, MemoryMap(map.base, protEnd - map.base, prot))
                memoryMap.put(protEnd, MemoryMap(protEnd, mapEnd - protEnd, map.prot))
            } else if (protEnd >= mapEnd) {
                val oldProt = map.prot
                memoryMap.remove(map.base)
                memoryMap.put(map.base, MemoryMap(map.base, address - map.base, oldProt))
                memoryMap.put(address, MemoryMap(address, mapEnd - address, prot))
            } else {
                val oldProt = map.prot
                memoryMap.remove(map.base)
                memoryMap.put(map.base, MemoryMap(map.base, address - map.base, oldProt))
                memoryMap.put(address, MemoryMap(address, aligned.toLong(), prot))
                memoryMap.put(protEnd, MemoryMap(protEnd, mapEnd - protEnd, oldProt))
            }
        }
        return 0
    }

    final override fun load(elfFile: File): Module {
        return load(elfFile, false)
    }

    final override fun load(libraryFile: LibraryFile): Module {
        return load(libraryFile, false)
    }

    final override fun load(elfFile: File, forceCallInit: Boolean): Module {
        return loadInternal(createLibraryFile(elfFile), forceCallInit)
    }

    protected abstract fun createLibraryFile(file: File): LibraryFile

    final override fun load(libraryFile: LibraryFile, forceCallInit: Boolean): Module {
        return loadInternal(libraryFile, forceCallInit)
    }

    protected abstract fun loadInternal(libraryFile: LibraryFile, forceCallInit: Boolean): Module

    @JvmField
    protected var callInitFunction = true

    final override fun disableCallInitFunction() {
        this.callInitFunction = false
    }

    override fun setCallInitFunction(callInit: Boolean) {
        this.callInitFunction = callInit
    }

    @JvmField
    protected val hookListeners: MutableList<HookListener> = ArrayList()

    final override fun addHookListener(listener: HookListener) {
        hookListeners.add(listener)
    }

    @JvmField
    protected var libraryResolver: LibraryResolver? = null

    override fun setLibraryResolver(libraryResolver: LibraryResolver) {
        libraryResolver.onSetToLoader(emulator)

        this.libraryResolver = libraryResolver
    }

    final override fun allocateStack(size: Int): VortexdbgPointer {
        val newAddr = sp - size
        val threadStackBase = Memory.STACK_BASE - Memory.STACK_SIZE_OF_MAIN_PAGE.toLong() * emulator.getPageAlign()
        if (newAddr <= threadStackBase) {
            throw IllegalStateException("Error! main thread stack point too large. sp=0x" + java.lang.Long.toHexString(sp) + ", threadStackBase=0x" + java.lang.Long.toHexString(threadStackBase))
        }
        setStackPoint(newAddr)
        val pointer = VortexdbgPointer.pointer(emulator, sp)
        assert(pointer != null)
        return pointer.setSize(size.toLong())
    }

    final override fun writeStackString(str: String): VortexdbgPointer {
        val data = str.toByteArray(StandardCharsets.UTF_8)
        return writeStackBytes(Arrays.copyOf(data, data.size + 1))
    }

    final override fun writeStackBytes(data: ByteArray): VortexdbgPointer {
        val size = ARM.alignSize(data.size)
        val pointer = allocateStack(size)
        assert(pointer != null)
        pointer.write(0, data, 0, data.size)
        return pointer
    }

    final override fun pointer(address: Long): VortexdbgPointer {
        return VortexdbgPointer.pointer(emulator, address)
    }

    private var stackBase: Long = 0
    @JvmField
    protected var stackSize: Int = 0

    override fun getStackBase(): Long {
        return stackBase
    }

    override fun getStackSize(): Int {
        return stackSize
    }

    final override fun setStackPoint(sp: Long) {
        if (this.sp == 0L) {
            this.stackBase = sp
        }
        this.sp = sp
        if (emulator.is32Bit()) {
            backend.reg_write(ArmConst.UC_ARM_REG_SP, sp)
        } else {
            backend.reg_write(Arm64Const.UC_ARM64_REG_SP, sp)
        }
    }

    override fun getStackPoint(): Long {
        return sp
    }

    @JvmField
    protected val moduleListeners: MutableList<ModuleListener> = ArrayList()

    final override fun addModuleListener(listener: ModuleListener) {
        moduleListeners.add(listener)
    }

    protected fun notifyModuleLoaded(module: Module) {
        for (listener in moduleListeners) {
            listener.onLoaded(emulator, module)
        }
    }

    @Throws(IOException::class)
    protected fun dump(pointer: Pointer, size: Long, outFile: File) {
        Files.newOutputStream(outFile.toPath()).use { outputStream: OutputStream ->
            var dump = 0
            while (dump < size) {
                var read = size - dump
                if (read > ARMEmulator.PAGE_ALIGN) {
                    read = ARMEmulator.PAGE_ALIGN.toLong()
                }
                val data = pointer.getByteArray(dump.toLong(), read.toInt())
                outputStream.write(data)
                dump += read.toInt()
            }
        }
    }

    protected fun mem_map(address: Long, size: Long, prot: Int, libraryName: String, align: Long): Alignment {
        val alignment = ARM.align(address, size, align)

        if (log.isDebugEnabled) {
            log.debug("[{}]0x{} - 0x{}, size=0x{}, prot={}", libraryName, java.lang.Long.toHexString(alignment.address), java.lang.Long.toHexString(alignment.address + alignment.size), java.lang.Long.toHexString(alignment.size), prot)
        }

        backend.mem_map(alignment.address, alignment.size, prot)
        if (mMapListener != null) {
            mMapListener!!.onMap(alignment.address, alignment.size, prot)
        }
        if (memoryMap.put(alignment.address, MemoryMap(alignment.address, alignment.size, prot)) != null) {
            log.warn("mem_map replace exists memory map address={}", java.lang.Long.toHexString(alignment.address))
        }
        return alignment
    }

    final override fun findModuleByAddress(address: Long): Module? {
        for (module in getLoadedModules()) {
            val base = getModuleBase(module)
            if (address >= base && address < base + module.size) {
                return module
            }
        }
        return null
    }

    protected open fun getModuleBase(module: Module): Long {
        return module.base
    }

    final override fun findModule(name: String): Module? {
        for (module in getLoadedModules()) {
            if (module.name == name) {
                return module
            }
        }
        return null
    }

    @JvmSuppressWildcards
    override fun loadVirtualModule(name: String, symbols: Map<String, VortexdbgPointer>): Module {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun serialize(out: DataOutput) {
        out.writeLong(sp)
        out.writeLong(mmapBaseAddress)
        out.writeLong(stackBase)
        out.writeLong(stackSize.toLong())
        out.writeInt(memoryMap.size)
        for (entry in memoryMap.entries) {
            val map = entry.value
            out.writeLong(entry.key)
            map.serialize(out)
            val pointer = VortexdbgPointer.pointer(emulator, map.base)
            assert(pointer != null)
            val data = pointer.getByteArray(0, map.size.toInt())
            out.write(data)
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AbstractLoader::class.java)
    }

}
