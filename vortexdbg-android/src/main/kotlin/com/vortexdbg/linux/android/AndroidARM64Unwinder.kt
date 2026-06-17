package com.vortexdbg.linux.android

import com.vortexdbg.Emulator
import com.vortexdbg.linux.LinuxModule
import com.vortexdbg.unwind.Frame
import com.vortexdbg.unwind.SimpleARM64Unwinder
import net.fornwall.jelf.DwarfCursor
import net.fornwall.jelf.DwarfCursor64
import net.fornwall.jelf.GnuEhFrameHeader
import net.fornwall.jelf.MemoizedObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.IOException

internal class AndroidARM64Unwinder(emulator: Emulator<*>) : SimpleARM64Unwinder(emulator) {

    private val context: DwarfCursor = DwarfCursor64(emulator)

    override fun unw_step(emulator: Emulator<*>, frame: Frame?): Frame? {
        try {
            val module = emulator.getMemory().findModuleByAddress(this.context.ip) as LinuxModule?
            val ehFrameHeader: MemoizedObject<GnuEhFrameHeader>? = if (module == null) null else module.ehFrameHeader
            if (ehFrameHeader != null) {
                val fun_ = this.context.ip - module!!.base
                val frameHeader = ehFrameHeader.getValue()
                val ret = if (frameHeader == null) null else frameHeader.dwarf_step(emulator, this, module, fun_, context)
                if (ret != null) {
                    return ret
                }
            }
        } catch (exception: RuntimeException) {
            log.warn("unw_step", exception)
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }

        return super.unw_step(emulator, frame)
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AndroidARM64Unwinder::class.java)
    }

}
