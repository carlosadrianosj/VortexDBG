package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import org.apache.commons.codec.DecoderException
import org.apache.commons.codec.binary.Hex

internal class MemoryCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        try {
            val divider = command.indexOf(",")
            val address = java.lang.Long.parseLong(command.substring(1, divider), 16)
            val pointer: Pointer? = VortexdbgPointer.pointer(emulator, address)
            if (pointer == null) {
                stub.makePacketAndSend("E01")
                return true
            }
            if (command.startsWith("m")) {
                val len = Integer.parseInt(command.substring(divider + 1), 16)
                val resp = Hex.encodeHexString(pointer.getByteArray(0, len)).toUpperCase()
                stub.makePacketAndSend(resp)
                return true
            } else {
                val dividerForValue = command.indexOf(":")
                val len = Integer.parseInt(command.substring(divider + 1, dividerForValue), 16)
                val `val` = Hex.decodeHex(command.substring(dividerForValue + 1).toCharArray())
                pointer.write(0, `val`, 0, len)
                stub.makePacketAndSend("OK")
                return true
            }
        } catch (e: BackendException) {
            stub.makePacketAndSend("E01")
            return true
        } catch (e: DecoderException) {
            throw IllegalStateException("process memory command failed: $command", e)
        }
    }

}
