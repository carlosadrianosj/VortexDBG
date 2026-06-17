package com.vortexdbg.debugger.ida.event

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.debugger.ida.DebuggerEvent
import com.vortexdbg.debugger.ida.Utils

import java.nio.ByteBuffer

class LoadModuleEvent(private val module: Module) : DebuggerEvent() {

    override fun pack(emulator: Emulator<*>): ByteArray {
        val buffer = ByteBuffer.allocate(0x100)
        buffer.put(Utils.pack_dd(0x2))
        buffer.put(Utils.pack_dd(0x80))
        buffer.put(Utils.pack_dd(emulator.getPid().toLong()))
        buffer.put(Utils.pack_dd(emulator.getPid().toLong()))
        buffer.put(Utils.pack_dq(module.base + 1))
        buffer.put(Utils.pack_dd(0x1))
        Utils.writeCString(buffer, module.getPath())
        buffer.put(Utils.pack_dq(module.base + 1))
        buffer.put(Utils.pack_dq(module.size + 1))
        buffer.put(Utils.pack_dq(0x0))
        return Utils.flipBuffer(buffer)
    }

}
