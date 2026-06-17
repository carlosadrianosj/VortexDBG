package com.vortexdbg.debugger.ida.event

import com.vortexdbg.Emulator
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.debugger.DebugServer
import com.vortexdbg.debugger.ida.DebuggerEvent
import com.vortexdbg.debugger.ida.Utils

import java.nio.ByteBuffer

class AttachExecutableEvent : DebuggerEvent() {

    override fun pack(emulator: Emulator<*>): ByteArray {
        val buffer = ByteBuffer.allocate(0x100)
        buffer.put(Utils.pack_dd(0x1))
        buffer.put(Utils.pack_dd(0x400))
        buffer.put(Utils.pack_dd(emulator.getPid().toLong()))
        buffer.put(Utils.pack_dd(emulator.getPid().toLong()))
        val pc = emulator.getContext<RegisterContext>().getPCPointer()
        if (emulator.is32Bit()) {
            buffer.put(Utils.pack_dq(pc.toUIntPeer() + 1))
        } else {
            buffer.put(Utils.pack_dq(pc.peer + 1))
        }
        buffer.put(1.toByte())
        Utils.writeCString(buffer, DebugServer.DEBUG_EXEC_NAME)
        buffer.put(Utils.pack_dq(1)) // base
        buffer.put(Utils.pack_dq(emulator.getPageAlign().toLong() + 1))
        buffer.put(Utils.pack_dq(1)) // base
        return Utils.flipBuffer(buffer)
    }
}
