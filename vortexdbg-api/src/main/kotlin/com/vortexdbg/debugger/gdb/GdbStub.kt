package com.vortexdbg.debugger.gdb

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.debugger.AbstractDebugServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.ArmConst
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.HashMap

/**
 * GdbStub class
 * @author Humberto Silva Naves
 */
class GdbStub(emulator: Emulator<*>) : AbstractDebugServer(emulator) {

    @JvmField
    val registers: IntArray

    private var lastPacket: String? = null
    private val currentInputPacket: StringBuilder
    private var packetChecksum: Int = 0
    private var packetFinished: Int = 0

    init {
        currentInputPacket = StringBuilder()

        if (emulator.is32Bit()) { // arm32
            registers = intArrayOf(
                ArmConst.UC_ARM_REG_R0,
                ArmConst.UC_ARM_REG_R1,
                ArmConst.UC_ARM_REG_R2,
                ArmConst.UC_ARM_REG_R3,
                ArmConst.UC_ARM_REG_R4,
                ArmConst.UC_ARM_REG_R5,
                ArmConst.UC_ARM_REG_R6,
                ArmConst.UC_ARM_REG_R7,
                ArmConst.UC_ARM_REG_R8,
                ArmConst.UC_ARM_REG_R9,
                ArmConst.UC_ARM_REG_R10,
                ArmConst.UC_ARM_REG_R11,
                ArmConst.UC_ARM_REG_R12,
                ArmConst.UC_ARM_REG_SP,
                ArmConst.UC_ARM_REG_LR,
                ArmConst.UC_ARM_REG_PC,
                ArmConst.UC_ARM_REG_CPSR
            )
        } else { // arm64
            registers = IntArray(34)
            for (i in 0..28) {
                registers[i] = Arm64Const.UC_ARM64_REG_X0 + i
            }
            registers[29] = Arm64Const.UC_ARM64_REG_X29
            registers[30] = Arm64Const.UC_ARM64_REG_X30
            registers[31] = Arm64Const.UC_ARM64_REG_SP
            registers[32] = Arm64Const.UC_ARM64_REG_PC
            registers[33] = Arm64Const.UC_ARM64_REG_NZCV
        }
    }

    override fun onServerStart() {
        val loaded: MutableList<Module> = ArrayList(emulator.getMemory().getLoadedModules())
        loaded.sortWith(Comparator { o1, o2 -> (o1.base - o2.base).toInt() })
        for (module in loaded) {
            System.err.println("[0x" + java.lang.Long.toHexString(module.base) + "]" + module.name)
        }
    }

    fun send(packet: String) {
        sendData((packet as java.lang.String).bytes)
    }

    private fun sendPacket(packet: String) {
        lastPacket = packet
        send(packet)
    }

    fun makePacketAndSend(data: String) {
        var data = data
        if (log.isDebugEnabled) {
            log.debug("makePacketAndSend: {}", data)
        }

        var checksum = 0
        data = escapePacketData(data)
        val sb = StringBuilder()
        sb.append("+")
        sb.append("$")
        for (i in 0 until data.length) {
            sb.append(data[i])
            checksum += data[i].code.toByte().toInt()
        }
        sb.append("#")
        sb.append(String.format("%02x", checksum and 0xff))
        sendPacket(sb.toString())
    }

    private fun escapePacketData(data: String): String {
        val sb = StringBuilder()
        for (i in 0 until data.length) {
            val c = data[i]
            if (c == '$' || c == '#' || c == '}') {
                sb.append("}")
                sb.append(c.code xor 0x20)
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    override fun processInput(input: ByteBuffer) {
        input.flip()

        while (input.hasRemaining()) {
            val c = input.get().toInt().toChar()
            if (currentInputPacket.length == 0) {
                when (c) {
                    '-' -> reTransmitLastPacket()
                    '+' -> {
                    } // Silently discard '+' packets
                    0x3.toChar() -> setSingleStep(1) // Ctrl-C requests
                    '$' -> {
                        currentInputPacket.append(c)
                        packetChecksum = 0
                        packetFinished = 0
                    }
                    else -> requestRetransmit()
                }
            } else {
                currentInputPacket.append(c)
                if (packetFinished > 0) {
                    if (++packetFinished == 3) {
                        if (checkPacket()) {
                            processCommand(currentInputPacket.substring(1, currentInputPacket.length - 3))
                        } else {
                            requestRetransmit()
                        }
                        currentInputPacket.setLength(0)
                    }
                } else if (c == '#') {
                    packetFinished = 1
                } else {
                    packetChecksum += c.code
                }
            }
        }

        input.clear()
    }

    private fun requestRetransmit() {
        send("-")
    }

    private fun reTransmitLastPacket() {
        send(lastPacket!!)
    }

    private fun checkPacket(): Boolean {
        return try {
            val checksum = Integer.parseInt(currentInputPacket.substring(currentInputPacket.length - 2), 16)
            checksum == (packetChecksum and 0xff)
        } catch (ex: NumberFormatException) {
            if (log.isDebugEnabled) {
                log.debug("checkPacket currentInputPacket={}", currentInputPacket, ex)
            }
            false
        }
    }

    private fun processCommand(command: String) {
        for (prefix in commands.keys) {
            if (command.startsWith(prefix)) {
                val cmd = commands[prefix]
                if (log.isDebugEnabled) {
                    log.debug("processCommand command={}, cmd={}", command, cmd)
                }
                if (cmd!!.processCommand(emulator, this, command)) {
                    return
                }
            }
        }
        if (log.isDebugEnabled) {
            log.warn("Unsupported command={}", command)
        }
        makePacketAndSend("")
    }

    override fun onHitBreakPoint(emulator: Emulator<*>, address: Long) {
        if (isDebuggerConnected()) {
            makePacketAndSend("S" + SIGTRAP)
        }
    }

    override fun onDebuggerExit(): Boolean {
        makePacketAndSend("W00")
        return true
    }

    override fun onDebuggerConnected() {
    }

    override fun toString(): String {
        return "gdb"
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(GdbStub::class.java)

        const val SIGTRAP = "05" /* Trace trap (POSIX).  */

        private val commands: MutableMap<String, GdbStubCommand>

        init {
            commands = HashMap()
            val commandContinue: GdbStubCommand = ContinueCommand()
            commands["c"] = commandContinue

            val commandStep: GdbStubCommand = StepCommand()
            commands["s"] = commandStep

            val commandBreakpoint: GdbStubCommand = BreakpointCommand()
            commands["z0"] = commandBreakpoint
            commands["Z0"] = commandBreakpoint

            val commandMemory: GdbStubCommand = MemoryCommand()
            commands["m"] = commandMemory
            commands["M"] = commandMemory

            val commandRegisters: GdbStubCommand = RegistersCommand()
            commands["g"] = commandRegisters
            commands["G"] = commandRegisters

            val commandRegister: GdbStubCommand = RegisterCommand()
            commands["p"] = commandRegister
            commands["P"] = commandRegister

            val commandKill: GdbStubCommand = KillCommand()
            commands["k"] = commandKill

            val commandEnableExtendedMode: GdbStubCommand = EnableExtendedModeCommand()
            commands["!"] = commandEnableExtendedMode

            val commandLastSignal: GdbStubCommand = LastSignalCommand()
            commands["?"] = commandLastSignal

            val commandDetach: GdbStubCommand = DetachCommand()
            commands["D"] = commandDetach

            val commandQuery: GdbStubCommand = QueryCommand()
            commands["q"] = commandQuery

            val commandSetThread: GdbStubCommand = SetThreadCommand()
            commands["H"] = commandSetThread

            val commandVCont: GdbStubCommand = ExtendedCommand()
            commands["vCont"] = commandVCont
        }
    }
}
