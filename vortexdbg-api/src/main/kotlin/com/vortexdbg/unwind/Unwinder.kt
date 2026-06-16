package com.vortexdbg.unwind

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Symbol
import com.vortexdbg.arm.AbstractARMDebugger
import com.vortexdbg.memory.MemRegion
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.github.zhkl0228.demumble.DemanglerFactory
import com.github.zhkl0228.demumble.GccDemangler

abstract class Unwinder protected constructor(@JvmField protected val emulator: Emulator<*>) {

    abstract fun createFrame(ip: VortexdbgPointer?, fp: VortexdbgPointer?): Frame?

    protected abstract fun unw_step(emulator: Emulator<*>, frame: Frame?): Frame?

    protected abstract fun getBaseFormat(): String

    fun getFrames(maxDepth: Int): MutableList<Frame> {
        val frames: MutableList<Frame> = java.util.ArrayList()
        var frame: Frame? = null
        while (unw_step(emulator, frame).also { frame = it } != null) {
            if (frame!!.isFinish()) break
            frames.add(frame!!)
            if (frames.size >= maxDepth) break
        }
        return frames
    }

    fun unwind() {
        val memory = emulator.getMemory()
        val maxLengthSoName = memory.getMaxLengthLibraryName()
        var hasTrace = false

        var frame: Frame? = null
        while (unw_step(emulator, frame).also { frame = it } != null) {
            if (frame!!.isFinish()) {
                if (!hasTrace) {
                    println("Decode backtrace finish")
                }
                return
            }

            hasTrace = true
            printFrameElement(maxLengthSoName, memory, frame!!.ip!!)
        }

        if (!hasTrace) {
            System.err.println("Decode backtrace failed.")
        }
    }

    private fun printFrameElement(maxLengthSoName: String, memory: Memory, ip: VortexdbgPointer) {
        val maxLength = maxLengthSoName.length
        val svcMemory = emulator.getSvcMemory()
        val region: MemRegion? = svcMemory.findRegion(ip.peer)
        val module: Module? = if (region != null) null else AbstractARMDebugger.findModuleByAddress(emulator, ip.peer)
        val sb = StringBuilder()
        val format = getBaseFormat()
        if (module != null) {
            sb.append(String.format(format, module.base)).append(String.format(format, ip.peer))
            sb.append(String.format("[%" + maxLength + "s]", module.name))
            sb.append(String.format("[0x%0" + java.lang.Long.toHexString(memory.getMaxSizeOfLibrary()).length + "x]", ip.peer - module.base))

            val symbol: Symbol? = module.findClosestSymbolByAddress(ip.peer, false)
            if (symbol != null && ip.peer - symbol.getAddress() <= SYMBOL_SIZE) {
                val demangler: GccDemangler = DemanglerFactory.createDemangler()
                sb.append(" ").append(demangler.demangle(symbol.getName())).append(" + 0x").append(java.lang.Long.toHexString(ip.peer - (symbol.getAddress() and 1.inv().toLong())))
            }
        } else {
            sb.append(String.format(format, 0)).append(String.format(format, ip.peer))
            if (region == null) {
                sb.append(String.format("[%" + maxLength + "s]", "0x" + java.lang.Long.toHexString(ip.peer)))
            } else {
                sb.append(String.format("[%" + maxLength + "s]", region.getName().substring(0, Math.min(maxLength, region.getName().length))))
            }
            if (ip.peer >= svcMemory.getBase()) {
                sb.append(String.format("[0x%0" + java.lang.Long.toHexString(memory.getMaxSizeOfLibrary()).length + "x]", ip.peer - svcMemory.getBase()))
            }
        }
        println(sb)
    }

    companion object {
        const val SYMBOL_SIZE = 0x1000
    }

}
