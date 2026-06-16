package com.vortexdbg.memory

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Symbol
import com.vortexdbg.unwind.Frame
import com.vortexdbg.unwind.Unwinder
import com.github.zhkl0228.demumble.DemanglerFactory
import com.github.zhkl0228.demumble.GccDemangler

import java.io.Closeable
import java.io.PrintStream
import java.util.ArrayList
import java.util.LinkedHashMap

class MemoryTracker(emulator: Emulator<*>) : MMapListener, Closeable {

    private val emulator: Emulator<*> = emulator
    private val previousListener: MMapListener?
    private val allocations: MutableMap<Long, AllocationRecord> = LinkedHashMap()
    private val startTime: Long
    private var totalAllocations = 0
    private var totalDeallocations = 0

    init {
        val memory = emulator.getMemory()
        this.previousListener = memory.getMMapListener()
        memory.setMMapListener(this)
        this.startTime = System.nanoTime()
    }

    override fun onMap(address: Long, size: Long, perms: Int) {
        val guestBt = captureGuestBacktrace()
        val hostBt = Throwable().stackTrace
        allocations.put(address, AllocationRecord(address, size, perms, guestBt, hostBt))
        totalAllocations++
        if (previousListener != null) {
            previousListener.onMap(address, size, perms)
        }
    }

    override fun onProtect(address: Long, size: Long, perms: Int): Int {
        if (previousListener != null) {
            return previousListener.onProtect(address, size, perms)
        }
        return perms
    }

    override fun onUnmap(address: Long, size: Long) {
        removeOverlapping(address, size)
        totalDeallocations++
        if (previousListener != null) {
            previousListener.onUnmap(address, size)
        }
    }

    private fun removeOverlapping(unmapAddr: Long, unmapSize: Long) {
        val unmapEnd = unmapAddr + unmapSize
        val it = allocations.entries.iterator()
        while (it.hasNext()) {
            val rec = it.next().value
            val recEnd = rec.address + rec.size
            if (rec.address < unmapEnd && recEnd > unmapAddr) {
                it.remove()
            }
        }
    }

    private fun captureGuestBacktrace(): Array<String> {
        try {
            val unwinder = emulator.getUnwinder()
            val frames = unwinder.getFrames(20)
            val result = arrayOfNulls<String>(frames.size)
            val memory = emulator.getMemory()
            for (i in frames.indices) {
                val pc = frames.get(i).ip!!.peer
                result[i] = formatFrame(i, pc, memory)
            }
            @Suppress("UNCHECKED_CAST")
            return result as Array<String>
        } catch (e: Exception) {
            return arrayOf("<backtrace unavailable: " + e.message + ">")
        }
    }

    private fun formatFrame(index: Int, pc: Long, memory: Memory): String {
        val sb = StringBuilder()
        sb.append("#").append(index).append(" 0x").append(java.lang.Long.toHexString(pc))

        val module = memory.findModuleByAddress(pc)
        if (module != null) {
            val offset = pc - module.base
            sb.append(" ").append(module.name).append("+0x").append(java.lang.Long.toHexString(offset))

            val symbol = module.findClosestSymbolByAddress(pc, false)
            if (symbol != null && pc - symbol.getAddress() <= Unwinder.SYMBOL_SIZE) {
                val demangler = DemanglerFactory.createDemangler()
                sb.append(" (").append(demangler.demangle(symbol.getName()))
                        .append("+0x").append(java.lang.Long.toHexString(pc - (symbol.getAddress() and 1L.inv())))
                        .append(")")
            }
        }
        return sb.toString()
    }

    fun getLeaks(): List<AllocationRecord> {
        return ArrayList(allocations.values)
    }

    fun getTotalAllocations(): Int {
        return totalAllocations
    }

    fun getTotalDeallocations(): Int {
        return totalDeallocations
    }

    fun getTotalLeakedSize(): Long {
        var total: Long = 0
        for (rec in allocations.values) {
            total += rec.size
        }
        return total
    }

    fun printReport() {
        printReport(System.out)
    }

    fun printReport(out: PrintStream) {
        val durationMs = (System.nanoTime() - startTime) / 1_000_000
        val leaks = getLeaks()
        val leakedSize = getTotalLeakedSize()

        out.println("=== Memory Leak Report ===")
        out.println("Backend: " + emulator.getBackend().javaClass.simpleName)
        out.println("Tracking duration: " + durationMs + "ms")
        out.println("Total allocations: " + totalAllocations)
        out.println("Total deallocations: " + totalDeallocations)
        out.println("Leaked blocks: " + leaks.size)
        out.println("Total leaked size: " + leakedSize + " bytes (" + formatSize(leakedSize) + ")")

        for (i in leaks.indices) {
            val rec = leaks.get(i)
            out.println()
            out.println("--- Leak #" + (i + 1) + " ---")
            out.println("Address: 0x" + java.lang.Long.toHexString(rec.address) +
                    ", Size: " + rec.size + " (" + formatSize(rec.size) +
                    "), Perms: " + formatPerms(rec.perms))

            if (rec.guestBacktrace.size > 0) {
                out.println("Guest Backtrace:")
                for (frame in rec.guestBacktrace) {
                    out.println("  " + frame)
                }
            }

            if (rec.hostStackTrace.size > 0) {
                out.println("Host Stack Trace:")
                for (element in rec.hostStackTrace) {
                    val className = element.className
                    if (className.startsWith("com.vortexdbg.")) {
                        out.println("  " + element)
                    }
                }
            }
        }

        if (leaks.isEmpty()) {
            out.println()
            out.println("No memory leaks detected.")
        }
    }

    override fun close() {
        emulator.getMemory().setMMapListener(previousListener)
        printReport()
    }

    companion object {
        @JvmStatic
        private fun formatSize(bytes: Long): String {
            if (bytes >= 1024 * 1024) {
                return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            } else if (bytes >= 1024) {
                return String.format("%.1f KB", bytes / 1024.0)
            }
            return bytes.toString() + " B"
        }

        @JvmStatic
        private fun formatPerms(perms: Int): String {
            return (if ((perms and 1) != 0) "r" else "-") +
                    (if ((perms and 2) != 0) "w" else "-") +
                    (if ((perms and 4) != 0) "x" else "-")
        }
    }

}
