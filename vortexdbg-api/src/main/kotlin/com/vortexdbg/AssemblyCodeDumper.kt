package com.vortexdbg

import capstone.Arm64_const
import capstone.Arm_const
import unicorn.Arm64Const
import capstone.api.Instruction
import capstone.api.RegsAccess
import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.arm.InstructionVisitor
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.backend.CodeHook
import com.vortexdbg.arm.backend.UnHook
import com.vortexdbg.listener.TraceCodeListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.PrintStream
import java.util.Arrays
import java.util.regex.Pattern

/**
 * my code hook
 * Created by zhkl0228 on 2017/5/2.
 */

class AssemblyCodeDumper(
    private val emulator: Emulator<*>,
    begin: Long,
    end: Long,
    private val listener: TraceCodeListener?
) : CodeHook, TraceHook {

    private val traceBegin: Long = begin
    private val traceEnd: Long = end
    private val maxLengthLibraryName: Int

    init {
        val memory = emulator.getMemory()
        if (begin > end) {
            maxLengthLibraryName = memory.getMaxLengthLibraryName().length
        } else {
            var value = 0
            for (module in memory.getLoadedModules()) {
                val min = Math.max(begin, module.base)
                val max = Math.min(end, module.base + module.size)
                if (min < max) {
                    val length = module.name.length
                    if (length > value) {
                        value = length
                    }
                }
            }
            maxLengthLibraryName = value
        }
    }

    private var unHook: UnHook? = null

    override fun onAttach(unHook: UnHook) {
        if (this.unHook != null) {
            throw IllegalStateException()
        }
        this.unHook = unHook
    }

    override fun detach() {
        if (unHook != null) {
            unHook!!.unhook()
            unHook = null
        }
    }

    override fun stopTrace() {
        detach()
        IOUtils.close(redirect)
        redirect = null
    }

    private fun canTrace(address: Long): Boolean {
        return (traceBegin > traceEnd || (address >= traceBegin && address <= traceEnd))
    }

    private var redirect: PrintStream? = null

    override fun setRedirect(redirect: PrintStream?) {
        this.redirect = redirect
    }

    private var lastInstructionWritePrinter: RegAccessPrinter? = null

    override fun hook(backend: Backend, address: Long, size: Int, user: Any?) {
        if (canTrace(address)) {
            try {
                var out = System.err
                if (redirect != null) {
                    out = redirect!!
                }
                val insns = emulator.printAssemble(out, address, size, maxLengthLibraryName, object : InstructionVisitor {
                    override fun visitLast(builder: StringBuilder) {
                        if (lastInstructionWritePrinter != null) {
                            lastInstructionWritePrinter!!.print(emulator, backend, builder, address)
                        }
                    }

                    override fun visit(builder: StringBuilder, ins: Instruction) {
                        hookMemoryAccess(backend, ins, builder)

                        val regsAccess = ins.regsAccess()
                        if (regsAccess != null) {
                            val regsRead = regsAccess.getRegsRead()
                            val readPrinter = RegAccessPrinter(address, ins, regsRead, false)
                            readPrinter.print(emulator, backend, builder, address)

                            val regWrite = regsAccess.getRegsWrite()
                            if (regWrite.size > 0) {
                                lastInstructionWritePrinter = RegAccessPrinter(address + size, ins, regWrite, true)
                            }
                        }
                    }
                })
                if (listener != null) {
                    if (insns.size != 1) {
                        throw IllegalStateException("insns=" + Arrays.toString(insns))
                    }
                    listener.onInstruction(emulator, address, insns[0])
                }
            } catch (e: BackendException) {
                throw IllegalStateException(e)
            }
        }
    }

    private fun hookMemoryAccess(backend: Backend, ins: Instruction, builder: StringBuilder) {
        try {
            var mnemonic = ins.getMnemonic() ?: return
            mnemonic = mnemonic.lowercase()

            val isLoad: Boolean
            if (LOAD_PATTERN.matcher(mnemonic).matches()) {
                isLoad = true
            } else if (STORE_PATTERN.matcher(mnemonic).matches()) {
                isLoad = false
            } else {
                return
            }

            val tag = if (isLoad) "r" else "w"
            if (emulator.is32Bit()) {
                val opInfo = ins.getOperands() as capstone.api.arm.OpInfo
                var memOperand: capstone.api.arm.Operand? = null
                for (op in opInfo.getOperands()) {
                    if (op.getType() == Arm_const.ARM_OP_MEM) {
                        memOperand = op
                        break
                    }
                }
                if (memOperand == null) {
                    return
                }
                val mem = memOperand.getValue().getMem()
                val baseValue = if (mem.getBase() != 0) backend.reg_read(ins.mapToUnicornReg(mem.getBase())).toLong() else 0L
                val indexValue = if (mem.getIndex() != 0) backend.reg_read(ins.mapToUnicornReg(mem.getIndex())).toLong() else 0L
                val lshift = mem.getLshift()
                var shiftedIndex = if (lshift != 0) indexValue shl lshift else indexValue
                if (memOperand.isSubtracted()) {
                    shiftedIndex = -shiftedIndex
                }
                val absAddr = baseValue + shiftedIndex + mem.getDisp()
                val size = getArm32AccessSize(mnemonic, opInfo)
                builder.append(String.format(" (%s 0x%x %d)", tag, absAddr, size))
            } else {
                val opInfo = ins.getOperands() as capstone.api.arm64.OpInfo
                var memOperand: capstone.api.arm64.Operand? = null
                for (op in opInfo.getOperands()) {
                    if (op.getType() == Arm64_const.ARM64_OP_MEM) {
                        memOperand = op
                        break
                    }
                }
                if (memOperand == null) {
                    return
                }
                val mem = memOperand.getValue().getMem()
                val baseValue = if (mem.getBase() != 0) readArm64Reg(backend, ins.mapToUnicornReg(mem.getBase())).toLong() else 0L
                val indexValue = if (mem.getIndex() != 0) readArm64Reg(backend, ins.mapToUnicornReg(mem.getIndex())).toLong() else 0L
                var shiftedIndex = indexValue
                val shift = memOperand.getShift()
                if (shift != null && shift.getValue() != 0) {
                    shiftedIndex = indexValue shl shift.getValue()
                }
                val absAddr = baseValue + shiftedIndex + mem.getDisp()
                val elemSize = getArm64ElemSize(ins, mnemonic, opInfo)
                builder.append(String.format(" (%s 0x%x %d)", tag, absAddr, elemSize))
                if (mnemonic.startsWith("ldp") || mnemonic.startsWith("stp")) {
                    builder.append(String.format(" (%s 0x%x %d)", tag, absAddr + elemSize, elemSize))
                }
            }
        } catch (e: Exception) {
            builder.append(" ; [mem_abs calc error: ").append(e.message).append("]")
            log.warn("hookMemoryAccess failed", e)
        }
    }

    private fun getArm32AccessSize(mnemonic: String, opInfo: capstone.api.arm.OpInfo): Int {
        if (mnemonic.startsWith("ldrb") || mnemonic.startsWith("strb") || mnemonic.startsWith("ldrsb")) {
            return 1
        }
        if (mnemonic.startsWith("ldrh") || mnemonic.startsWith("strh") || mnemonic.startsWith("ldrsh")) {
            return 2
        }
        if (mnemonic.startsWith("ldm") || mnemonic.startsWith("stm")) {
            var regCount = 0
            for (op in opInfo.getOperands()) {
                if (op.getType() == Arm_const.ARM_OP_REG) {
                    regCount++
                }
            }
            return 4 * Math.max(regCount, 1)
        }
        return 4
    }

    private fun getArm64ElemSize(ins: Instruction, mnemonic: String, opInfo: capstone.api.arm64.OpInfo): Int {
        if (mnemonic.endsWith("b")) return 1 // ldrb, strb, ldurb, sturb, ldrsb
        if (mnemonic.endsWith("h")) return 2 // ldrh, strh, ldurh, sturh, ldrsh
        if (mnemonic.endsWith("w")) return 4 // ldrsw, ldpsw
        // Infer size from first register operand (map to unicorn regId for range checks)
        for (op in opInfo.getOperands()) {
            if (op.getType() == Arm64_const.ARM64_OP_REG) {
                return arm64RegSize(ins.mapToUnicornReg(op.getValue().getReg()))
            }
        }
        return 8
    }

    @Throws(BackendException::class)
    private fun readArm64Reg(backend: Backend, regId: Int): Number {
        // XZR/WZR always read as zero
        if (regId == Arm64Const.UC_ARM64_REG_XZR || regId == Arm64Const.UC_ARM64_REG_WZR) return 0L
        // WSP is the 32-bit view of SP; map to SP
        if (regId == Arm64Const.UC_ARM64_REG_WSP) return backend.reg_read(Arm64Const.UC_ARM64_REG_SP)
        return backend.reg_read(regId)
    }

    private fun arm64RegSize(regId: Int): Int {
        if (regId >= Arm64Const.UC_ARM64_REG_B0 && regId <= Arm64Const.UC_ARM64_REG_B31) return 1
        if (regId >= Arm64Const.UC_ARM64_REG_D0 && regId <= Arm64Const.UC_ARM64_REG_D31) return 8
        if (regId >= Arm64Const.UC_ARM64_REG_H0 && regId <= Arm64Const.UC_ARM64_REG_H31) return 2
        if (regId >= Arm64Const.UC_ARM64_REG_Q0 && regId <= Arm64Const.UC_ARM64_REG_Q31) return 16
        if (regId >= Arm64Const.UC_ARM64_REG_S0 && regId <= Arm64Const.UC_ARM64_REG_S31) return 4
        if ((regId >= Arm64Const.UC_ARM64_REG_W0 && regId <= Arm64Const.UC_ARM64_REG_W30) || regId == Arm64Const.UC_ARM64_REG_WZR) return 4
        return 8 // X0-X28, X29, X30, SP, XZR
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AssemblyCodeDumper::class.java)

        private val LOAD_PATTERN: Pattern = Pattern.compile("^(ldr|ldrb|ldrh|ldrsb|ldrsh|ldur|ldurb|ldurh|ldp|ldm)($|\\.|\\s).*")
        private val STORE_PATTERN: Pattern = Pattern.compile("^(str|strb|strh|stur|sturb|sturh|stp|stm)($|\\.|\\s).*")
    }

}
