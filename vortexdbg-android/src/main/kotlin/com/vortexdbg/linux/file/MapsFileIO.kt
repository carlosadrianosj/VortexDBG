package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.file.FileIO
import com.vortexdbg.memory.MemRegion
import com.vortexdbg.memory.Memory
import org.slf4j.LoggerFactory
import unicorn.UnicornConst

import java.util.ArrayList
import java.util.Collections

open class MapsFileIO : ByteArrayFileIO, FileIO {

    constructor(emulator: Emulator<*>, oflags: Int, path: String, modules: Collection<Module>) :
            super(oflags, path, getMapsData(emulator, modules, null))

    protected constructor(emulator: Emulator<*>, oflags: Int, path: String, modules: Collection<Module>, additionContent: String?) :
            this(oflags, path, getMapsData(emulator, modules, additionContent))

    protected constructor(oflags: Int, path: String, bytes: ByteArray) : super(oflags, path, bytes)

    private class MapItem(
        val start: Long,
        val end: Long,
        private val perms: Int,
        private val offset: Int,
        private val device: String,
        private val label: String
    ) {
        override fun toString(): String {
            val builder = StringBuilder()
            builder.append(String.format("%08x-%08x", start, end)).append(' ')
            if ((perms and UnicornConst.UC_PROT_READ) != 0) {
                builder.append('r')
            } else {
                builder.append('-')
            }
            if ((perms and UnicornConst.UC_PROT_WRITE) != 0) {
                builder.append('w')
            } else {
                builder.append('-')
            }
            if ((perms and UnicornConst.UC_PROT_EXEC) != 0) {
                builder.append('x')
            } else {
                builder.append('-')
            }
            builder.append("p ")
            builder.append(String.format("%08x", offset))
            builder.append(" ").append(device).append(" 0")
            for (i in 0 until 10) {
                builder.append(' ')
            }
            builder.append(label)
            builder.append('\n')
            return builder.toString()
        }
    }

    override fun ioctl(emulator: Emulator<*>, request: Long, argp: Long): Int {
        return 0
    }

    companion object {
        private val log = LoggerFactory.getLogger(MapsFileIO::class.java)

        @JvmStatic
        protected fun getMapsData(emulator: Emulator<*>, modules: Collection<Module>, additionContent: String?): ByteArray {
            val list: MutableList<MemRegion> = ArrayList(modules.size)
            for (module in modules) {
                list.addAll(module.getRegions())
            }
            Collections.sort(list)
            val items: MutableList<MapItem> = ArrayList()
            for (memRegion in list) {
                items.add(MapItem(memRegion.virtualAddress, memRegion.end, memRegion.perms, 0, "b3:19", memRegion.getName()))
            }
            val stackSize = Memory.STACK_SIZE_OF_PAGE.toLong() * emulator.getPageAlign()
            items.add(MapItem(Memory.STACK_BASE - stackSize, Memory.STACK_BASE, UnicornConst.UC_PROT_WRITE or UnicornConst.UC_PROT_READ, 0, "00:00", "[stack]"))

            val mapItems: MutableList<MapItem> = ArrayList()
            for (memoryMap in emulator.getMemory().getMemoryMap()) {
                var contains = false
                for (item in items) {
                    if (Math.max(memoryMap.base, item.start) <= Math.min(memoryMap.base + memoryMap.size, item.end)) {
                        contains = true
                        break
                    }
                }
                if (!contains) {
                    mapItems.add(MapItem(memoryMap.base, memoryMap.base + memoryMap.size, memoryMap.prot, 0, "00:00", "anonymous"))
                }
            }
            items.addAll(mapItems)

            val builder = StringBuilder()
            for (item in items) {
                builder.append(item)
            }
            if (additionContent != null) {
                builder.append(additionContent).append('\n')
            }
            if (log.isDebugEnabled) {
                log.debug("\n{}", builder)
            }

            return builder.toString().toByteArray()
        }
    }
}
