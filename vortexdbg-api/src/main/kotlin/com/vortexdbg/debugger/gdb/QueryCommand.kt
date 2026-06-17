package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator
import com.vortexdbg.debugger.DebugServer
import org.apache.commons.codec.DecoderException
import org.apache.commons.codec.binary.Hex
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

internal class QueryCommand : GdbStubCommand {

    override fun processCommand(emulator: Emulator<*>, stub: GdbStub, command: String): Boolean {
        if (command.startsWith("qSupported")) {
            stub.makePacketAndSend("PacketSize=" + DebugServer.PACKET_SIZE + ";vContSupported+;multiprocess-;xmlRegisters=arm")
            return true
        }
        if (command.startsWith("qAttached")) {
            stub.makePacketAndSend("1")
            return true
        }
        if (command.startsWith("qC")) {
            stub.makePacketAndSend("QC1")
            return true
        }
        if (command.startsWith("qfThreadInfo")) {
            stub.makePacketAndSend("m01")
            return true
        }
        if (command.startsWith("qsThreadInfo")) {
            stub.makePacketAndSend("l")
            return true
        }
        if (command.startsWith("qRcmd,")) {
            try {
                val cmd = String(Hex.decodeHex(command.substring(6).toCharArray()), StandardCharsets.UTF_8)
                if (log.isDebugEnabled) {
                    log.debug("qRcmd={}", cmd)
                }
                stub.makePacketAndSend("E01")
                return true
            } catch (e: DecoderException) {
                throw IllegalStateException(e)
            }
        }
        return false
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(QueryCommand::class.java)
    }

}
