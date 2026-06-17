package com.vortexdbg.linux.android

import com.vortexdbg.Emulator
import com.vortexdbg.linux.LinuxModule
import com.vortexdbg.unwind.Frame
import com.vortexdbg.unwind.SimpleARMUnwinder
import net.fornwall.jelf.ArmExIdx
import net.fornwall.jelf.DwarfCursor
import net.fornwall.jelf.DwarfCursor32
import net.fornwall.jelf.GnuEhFrameHeader
import net.fornwall.jelf.MemoizedObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.IOException

internal class AndroidARMUnwinder(emulator: Emulator<*>) : SimpleARMUnwinder(emulator) {

    private val context: DwarfCursor = DwarfCursor32(emulator)

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
            val armExIdx: MemoizedObject<ArmExIdx>? = if (module == null) null else module.armExIdx
            if (armExIdx != null) {
                val fun_ = this.context.ip - module!!.base
                return armExIdx.getValue().arm_exidx_step(emulator, this, module, fun_, context)
            }
        } catch (exception: RuntimeException) {
            log.warn("unw_step", exception)
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }

        return super.unw_step(emulator, frame)
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AndroidARMUnwinder::class.java)
    }

}
