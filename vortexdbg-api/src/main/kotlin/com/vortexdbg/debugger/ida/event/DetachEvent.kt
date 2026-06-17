package com.vortexdbg.debugger.ida.event

import com.vortexdbg.Emulator
import com.vortexdbg.debugger.ida.DebuggerEvent
import com.vortexdbg.debugger.ida.Utils

import java.nio.ByteBuffer

class DetachEvent : DebuggerEvent() {

    override fun pack(emulator: Emulator<*>): ByteArray {
        val buffer = ByteBuffer.allocate(0x20)
        buffer.put(Utils.pack_dd(0x1))
        buffer.put(Utils.pack_dd(0x800))
        buffer.put(Utils.pack_dd(emulator.getPid().toLong()))
        buffer.put(Utils.pack_dd(emulator.getPid().toLong()))
        buffer.put(Utils.pack_dq(0x100000000L))
        buffer.put(1.toByte())
        return Utils.flipBuffer(buffer)
    }
}
