package com.vortexdbg.arm

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.debugger.DebugRunnable
import com.vortexdbg.debugger.Debugger
import com.vortexdbg.debugger.FunctionCallListener
import com.vortexdbg.thread.RunnableTask
import keystone.Keystone
import keystone.KeystoneArchitecture
import keystone.KeystoneMode
import org.apache.commons.codec.DecoderException
import unicorn.ArmConst
import java.util.Scanner

internal class SimpleARMDebugger(emulator: Emulator<*>) : AbstractARMDebugger(emulator), Debugger {

    override fun traceFunctionCall(module: Module?, listener: FunctionCallListener) {
        val backend: Backend = emulator.getBackend()
        val hook: TraceFunctionCall = TraceFunctionCall32(emulator, listener)
        val begin = if (module == null) 1 else module.base
        val end = if (module == null) 0 else module.base + module.size
        backend.hook_add_new(hook, begin, end, emulator)
    }

    @Throws(Exception::class)
    override fun loop(emulator: Emulator<*>, address: Long, size: Int, runnable: DebugRunnable<*>?) {
        val backend: Backend = emulator.getBackend()
        val thumb = ARM.isThumb(backend)
        var nextAddress: Long = 0

        try {
            if (address != -1L) {
                val runningTask = emulator.getThreadDispatcher().getRunningTask()
                println("debugger break at: 0x" + java.lang.Long.toHexString(address) + (if (runningTask == null) "" else (" @ $runningTask")))
                emulator.showRegs()
            }
            if (address > 0) {
                nextAddress = disassemble(emulator, address, size, thumb)
            }
        } catch (e: BackendException) {
            e.printStackTrace(System.err)
        }

        var scanner = Scanner(System.`in`)
        var line: String?
        while (scanner.nextLine().also { line = it } != null) {
            val cur = line!!.trim()
            try {
                if ("d" == cur || "dis" == cur) {
                    emulator.showRegs()
                    disassemble(emulator, address, size, thumb)
                    continue
                }
                if (cur.startsWith("d0x")) {
                    var s = cur
                    if (s.endsWith("L")) {
                        s = s.substring(0, s.length - 1)
                    }
                    val da = java.lang.Long.parseLong(s.substring(3), 16)
                    disassembleBlock(emulator, da and 0xfffffffeL, (da and 1) == 1L)
                    continue
                }
                if (handleWriteCommand(backend, cur)) {
                    continue
                }
                if (handleCommon(backend, cur, address, size, nextAddress, runnable)) {
                    break
                }
                if (scannerNeedsRefresh) {
                    scanner = Scanner(System.`in`)
                    scannerNeedsRefresh = false
                }
            } catch (e: RuntimeException) {
                e.printStackTrace(System.err)
            } catch (e: DecoderException) {
                e.printStackTrace(System.err)
            }
        }
    }

    override fun showHelp(address: Long) {
        super.showHelp(address)
        println("s(blx): execute util BLX mnemonic, low performance")
        println()
        println("m(op) [size]: show memory, default size is 0x70, size may hex or decimal")
        println("mr0-mr7, mfp, mip, msp [size]: show memory of specified register")
        println("m(address) [size]: show memory of specified address, address must start with 0x")
        println("  append 's' to read as C string, e.g. mr0s, m0x1234s")
        println("  append 'std' to read as std::string, e.g. mr0std, m0x1234std")
        println()
        println("wr0-wr7, wfp, wip, wsp <value>: write specified register")
        println("wb(address), ws(address), wi(address) <value>: write (byte, short, integer) memory of specified address, address must start with 0x")
        showCommonHelp(address)
    }

    override fun resolveWriteRegister(command: String): Int {
        if (command.startsWith("wr") && command.length == 3) {
            val c = command[2]
            if (c in '0'..'8') {
                val r = c - '0'
                return ArmConst.UC_ARM_REG_R0 + r
            }
        } else if ("wfp" == command) {
            return ArmConst.UC_ARM_REG_FP
        } else if ("wip" == command) {
            return ArmConst.UC_ARM_REG_IP
        } else if ("wsp" == command) {
            return ArmConst.UC_ARM_REG_SP
        }
        return -1
    }

    override fun showWriteRegs(reg: Int) {
        ARM.showRegs(emulator, intArrayOf(reg))
    }

    override fun showWriteHelp() {
        println("wr0-wr8, wfp, wip, wsp <value>: write specified register")
        println("wb(address), ws(address), wi(address) <value>: write (byte, short, integer) memory of specified address, address must start with 0x")
    }

    override fun resolveRegister(command: String, nameOut: Array<String?>): Int {
        if (command.startsWith("mr") && command.length == 3) {
            val c = command[2]
            if (c in '0'..'7') {
                val r = c - '0'
                nameOut[0] = "r$r"
                return ArmConst.UC_ARM_REG_R0 + r
            }
        } else if ("mfp" == command) {
            nameOut[0] = "fp"
            return ArmConst.UC_ARM_REG_FP
        } else if ("mip" == command) {
            nameOut[0] = "ip"
            return ArmConst.UC_ARM_REG_IP
        } else if ("msp" == command) {
            nameOut[0] = "sp"
            return ArmConst.UC_ARM_REG_SP
        }
        return -1
    }

    override fun createKeystone(isThumb: Boolean): Keystone {
        return Keystone(KeystoneArchitecture.Arm, if (isThumb) KeystoneMode.ArmThumb else KeystoneMode.Arm)
    }
}
