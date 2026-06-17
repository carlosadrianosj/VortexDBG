package com.vortexdbg.arm

import capstone.api.Instruction
import capstone.api.OpShift
import capstone.api.arm.MemType
import capstone.api.arm.Operand
import com.vortexdbg.Alignment
import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.Utils
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.memory.MemRegion
import com.vortexdbg.memory.Memory
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer
import org.apache.commons.codec.binary.Hex
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const
import unicorn.ArmConst
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.Locale

/**
 * arm utils
 * Created by zhkl0228 on 2017/5/11.
 */
object ARM {

    @JvmStatic
    fun isThumb(backend: Backend): Boolean {
        return Cpsr.getArm(backend).isThumb()
    }

    /**
     * 是否为thumb32
     */
    @JvmStatic
    fun isThumb32(ins: Short): Boolean {
        return (ins.toInt() and 0xe000) == 0xe000 && (ins.toInt() and 0x1800) != 0x0000
    }

    private fun appendCpsr(builder: StringBuilder, cpsr: Cpsr) {
        builder.append(
            String.format(
                Locale.US, " cpsr: N=%d, Z=%d, C=%d, V=%d, T=%d, mode=0b",
                if (cpsr.isNegative()) 1 else 0,
                if (cpsr.isZero()) 1 else 0,
                if (cpsr.hasCarry()) 1 else 0,
                if (cpsr.isOverflow()) 1 else 0,
                if (cpsr.isThumb()) 1 else 0
            )
        ).append(Integer.toBinaryString(cpsr.getMode()))
    }

    @JvmStatic
    fun showThumbRegs(emulator: Emulator<*>) {
        showRegs(emulator, THUMB_REGS)
    }

    @JvmStatic
    fun showRegs(emulator: Emulator<*>, regs: IntArray?) {
        var regsVar = regs
        val backend = emulator.getBackend()
        val thumb = isThumb(backend)
        if (regsVar == null || regsVar.size < 1) {
            regsVar = getAllRegisters(thumb)
        }
        val builder = StringBuilder()
        builder.append(">>>")
        for (reg in regsVar) {
            val number: Number
            val value: Int
            when (reg) {
                ArmConst.UC_ARM_REG_CPSR -> {
                    val cpsr = Cpsr.getArm(backend)
                    appendCpsr(builder, cpsr)
                }
                ArmConst.UC_ARM_REG_R0 -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " r0=0x%x", value))
                    if (value < 0) {
                        builder.append('(').append(value).append(')')
                    }
                }
                ArmConst.UC_ARM_REG_R1 -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " r1=0x%x", value))
                }
                ArmConst.UC_ARM_REG_R2 -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " r2=0x%x", value))
                }
                ArmConst.UC_ARM_REG_R3 -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " r3=0x%x", value))
                }
                ArmConst.UC_ARM_REG_R4 -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " r4=0x%x", value))
                }
                ArmConst.UC_ARM_REG_R5 -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " r5=0x%x", value))
                }
                ArmConst.UC_ARM_REG_R6 -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " r6=0x%x", value))
                }
                ArmConst.UC_ARM_REG_R7 -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " r7=0x%x", value))
                }
                ArmConst.UC_ARM_REG_R8 -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " r8=0x%x", value))
                }
                ArmConst.UC_ARM_REG_R9 -> { // UC_ARM_REG_SB
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " sb=0x%x", value))
                }
                ArmConst.UC_ARM_REG_R10 -> { // UC_ARM_REG_SL
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " sl=0x%x", value))
                }
                ArmConst.UC_ARM_REG_FP -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " fp=0x%x", value))
                }
                ArmConst.UC_ARM_REG_IP -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, " ip=0x%x", value))
                }
                ArmConst.UC_ARM_REG_SP -> {
                    number = backend.reg_read(reg)
                    value = number.toInt()
                    builder.append(String.format(Locale.US, "\n>>> SP=0x%x", value))
                }
                ArmConst.UC_ARM_REG_LR -> {
                    builder.append(String.format(Locale.US, " LR=%s", VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_LR)))
                }
                ArmConst.UC_ARM_REG_PC -> {
                    val pc = VortexdbgPointer.register(emulator, ArmConst.UC_ARM_REG_PC)
                    builder.append(String.format(Locale.US, " PC=%s", pc))
                }
                ArmConst.UC_ARM_REG_D0 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append("\n>>>")
                        builder.append(String.format(Locale.US, " d0=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D1 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d1=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D2 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d2=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D3 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d3=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D4 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d4=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D5 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d5=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D6 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d6=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D7 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d7=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D8 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append("\n>>>")
                        builder.append(String.format(Locale.US, " d8=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D9 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d9=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D10 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d10=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D11 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d11=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D12 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d12=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D13 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d13=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D14 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d14=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                ArmConst.UC_ARM_REG_D15 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " d15=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
            }
        }
        println(builder)
    }

    @JvmStatic
    fun showRegs64(emulator: Emulator<*>, regs: IntArray?) {
        var regsVar = regs
        val backend = emulator.getBackend()
        if (regsVar == null || regsVar.size < 1) {
            regsVar = getAll64Registers()
        }
        val builder = StringBuilder()
        builder.append(">>>")
        for (reg in regsVar) {
            val number: Number
            val value: Long
            when (reg) {
                Arm64Const.UC_ARM64_REG_NZCV -> {
                    val cpsr = Cpsr.getArm64(backend)
                    if (cpsr.isA32()) {
                        appendCpsr(builder, cpsr)
                    } else {
                        val el = cpsr.getEL()
                        builder.append(
                            String.format(
                                Locale.US, "\nnzcv: N=%d, Z=%d, C=%d, V=%d, EL%d, use SP_EL",
                                if (cpsr.isNegative()) 1 else 0,
                                if (cpsr.isZero()) 1 else 0,
                                if (cpsr.hasCarry()) 1 else 0,
                                if (cpsr.isOverflow()) 1 else 0,
                                el
                            )
                        ).append(if ((cpsr.getValue() and 1) == 0) 0 else el)
                    }
                }
                Arm64Const.UC_ARM64_REG_X0 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x0=0x%x", value))
                    if (value < 0) {
                        builder.append('(').append(value).append(')')
                    } else if ((value and 0x7fffffff00000000L) == 0L) {
                        val iv = value.toInt()
                        if (iv < 0) {
                            builder.append('(').append(iv).append(')')
                        }
                    }
                }
                Arm64Const.UC_ARM64_REG_X1 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x1=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X2 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x2=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X3 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x3=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X4 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x4=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X5 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x5=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X6 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x6=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X7 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x7=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X8 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x8=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X9 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x9=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X10 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x10=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X11 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x11=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X12 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x12=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X13 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x13=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X14 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x14=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X15 -> {
                    builder.append("\n>>>")
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x15=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X16 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x16=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X17 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x17=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X18 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x18=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X19 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x19=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X20 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x20=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X21 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x21=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X22 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x22=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X23 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x23=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X24 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x24=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X25 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x25=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X26 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x26=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X27 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x27=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_X28 -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " x28=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_FP -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, " fp=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_SP -> {
                    number = backend.reg_read(reg)
                    value = number.toLong()
                    builder.append(String.format(Locale.US, "\nSP=0x%x", value))
                }
                Arm64Const.UC_ARM64_REG_LR -> {
                    val lr = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_LR)
                    builder.append(String.format(Locale.US, "\nLR=%s", lr))
                }
                Arm64Const.UC_ARM64_REG_PC -> {
                    val pc = VortexdbgPointer.register(emulator, Arm64Const.UC_ARM64_REG_PC)
                    builder.append(String.format(Locale.US, "\nPC=%s", pc))
                }
                Arm64Const.UC_ARM64_REG_Q0 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append("\n>>>")
                        builder.append(String.format(Locale.US, " q0=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q1 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q1=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q2 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q2=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q3 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q3=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q4 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q4=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q5 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q5=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q6 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q6=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q7 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q7=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q8 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q8=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q9 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q9=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q10 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q10=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q11 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q11=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q12 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q12=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q13 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q13=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q14 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q14=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q15 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q15=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q16 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append("\n>>>")
                        builder.append(String.format(Locale.US, " q16=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q17 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q17=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q18 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q18=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q19 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q19=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q20 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q20=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q21 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q21=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q22 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q22=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q23 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q23=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q24 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q24=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q25 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q25=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q26 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q26=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q27 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q27=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q28 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q28=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q29 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q29=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q30 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q30=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
                Arm64Const.UC_ARM64_REG_Q31 -> {
                    val data = backend.reg_read_vector(reg)
                    if (data != null) {
                        builder.append(String.format(Locale.US, " q31=0x%s%s", newBigInteger(data).toString(16), Utils.decodeVectorRegister(data)))
                    }
                }
            }
        }
        println(builder)
    }

    @JvmStatic
    fun newBigInteger(data: ByteArray): BigInteger {
        if (data.size != 16) {
            throw IllegalStateException("data.length=" + data.size)
        }
        val copy = Arrays.copyOf(data, data.size)
        for (i in 0 until 8) {
            val b = copy[i]
            copy[i] = copy[15 - i]
            copy[15 - i] = b
        }
        val bytes = ByteArray(copy.size + 1)
        System.arraycopy(copy, 0, bytes, 1, copy.size) // makePositive
        return BigInteger(bytes)
    }

    private val ARM_ARG_REGS = intArrayOf(
        ArmConst.UC_ARM_REG_R0,
        ArmConst.UC_ARM_REG_R1,
        ArmConst.UC_ARM_REG_R2,
        ArmConst.UC_ARM_REG_R3
    )

    private val ARM64_ARG_REGS = intArrayOf(
        Arm64Const.UC_ARM64_REG_X0,
        Arm64Const.UC_ARM64_REG_X1,
        Arm64Const.UC_ARM64_REG_X2,
        Arm64Const.UC_ARM64_REG_X3,
        Arm64Const.UC_ARM64_REG_X4,
        Arm64Const.UC_ARM64_REG_X5,
        Arm64Const.UC_ARM64_REG_X6,
        Arm64Const.UC_ARM64_REG_X7
    )

    private val THUMB_REGS = intArrayOf(
        ArmConst.UC_ARM_REG_R0,
        ArmConst.UC_ARM_REG_R1,
        ArmConst.UC_ARM_REG_R2,
        ArmConst.UC_ARM_REG_R3,
        ArmConst.UC_ARM_REG_R4,
        ArmConst.UC_ARM_REG_R5,
        ArmConst.UC_ARM_REG_R6,
        ArmConst.UC_ARM_REG_R7,
        ArmConst.UC_ARM_REG_R8,
        ArmConst.UC_ARM_REG_SB,
        ArmConst.UC_ARM_REG_SL,

        ArmConst.UC_ARM_REG_FP,
        ArmConst.UC_ARM_REG_IP,

        ArmConst.UC_ARM_REG_SP,
        ArmConst.UC_ARM_REG_LR,
        ArmConst.UC_ARM_REG_PC,
        ArmConst.UC_ARM_REG_CPSR,

        ArmConst.UC_ARM_REG_D0,
        ArmConst.UC_ARM_REG_D1,
        ArmConst.UC_ARM_REG_D2,
        ArmConst.UC_ARM_REG_D3,
        ArmConst.UC_ARM_REG_D4,
        ArmConst.UC_ARM_REG_D5,
        ArmConst.UC_ARM_REG_D6,
        ArmConst.UC_ARM_REG_D7,
        ArmConst.UC_ARM_REG_D8,
        ArmConst.UC_ARM_REG_D9,
        ArmConst.UC_ARM_REG_D10,
        ArmConst.UC_ARM_REG_D11,
        ArmConst.UC_ARM_REG_D12,
        ArmConst.UC_ARM_REG_D13,
        ArmConst.UC_ARM_REG_D14,
        ArmConst.UC_ARM_REG_D15
    )

    private val ARM_REGS = intArrayOf(
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

        ArmConst.UC_ARM_REG_FP,
        ArmConst.UC_ARM_REG_IP,

        ArmConst.UC_ARM_REG_SP,
        ArmConst.UC_ARM_REG_LR,
        ArmConst.UC_ARM_REG_PC,
        ArmConst.UC_ARM_REG_CPSR,

        ArmConst.UC_ARM_REG_D0,
        ArmConst.UC_ARM_REG_D1,
        ArmConst.UC_ARM_REG_D2,
        ArmConst.UC_ARM_REG_D3,
        ArmConst.UC_ARM_REG_D4,
        ArmConst.UC_ARM_REG_D5,
        ArmConst.UC_ARM_REG_D6,
        ArmConst.UC_ARM_REG_D7,
        ArmConst.UC_ARM_REG_D8,
        ArmConst.UC_ARM_REG_D9,
        ArmConst.UC_ARM_REG_D10,
        ArmConst.UC_ARM_REG_D11,
        ArmConst.UC_ARM_REG_D12,
        ArmConst.UC_ARM_REG_D13,
        ArmConst.UC_ARM_REG_D14,
        ArmConst.UC_ARM_REG_D15
    )

    private val ARM64_REGS = intArrayOf(
        Arm64Const.UC_ARM64_REG_X0,
        Arm64Const.UC_ARM64_REG_X1,
        Arm64Const.UC_ARM64_REG_X2,
        Arm64Const.UC_ARM64_REG_X3,
        Arm64Const.UC_ARM64_REG_X4,
        Arm64Const.UC_ARM64_REG_X5,
        Arm64Const.UC_ARM64_REG_X6,
        Arm64Const.UC_ARM64_REG_X7,
        Arm64Const.UC_ARM64_REG_X8,
        Arm64Const.UC_ARM64_REG_X9,
        Arm64Const.UC_ARM64_REG_X10,
        Arm64Const.UC_ARM64_REG_X11,
        Arm64Const.UC_ARM64_REG_X12,
        Arm64Const.UC_ARM64_REG_X13,
        Arm64Const.UC_ARM64_REG_X14,
        Arm64Const.UC_ARM64_REG_X15,
        Arm64Const.UC_ARM64_REG_X16,
        Arm64Const.UC_ARM64_REG_X17,
        Arm64Const.UC_ARM64_REG_X18,
        Arm64Const.UC_ARM64_REG_X19,
        Arm64Const.UC_ARM64_REG_X20,
        Arm64Const.UC_ARM64_REG_X21,
        Arm64Const.UC_ARM64_REG_X22,
        Arm64Const.UC_ARM64_REG_X23,
        Arm64Const.UC_ARM64_REG_X24,
        Arm64Const.UC_ARM64_REG_X25,
        Arm64Const.UC_ARM64_REG_X26,
        Arm64Const.UC_ARM64_REG_X27,
        Arm64Const.UC_ARM64_REG_X28,

        Arm64Const.UC_ARM64_REG_FP,

        Arm64Const.UC_ARM64_REG_Q0,
        Arm64Const.UC_ARM64_REG_Q1,
        Arm64Const.UC_ARM64_REG_Q2,
        Arm64Const.UC_ARM64_REG_Q3,
        Arm64Const.UC_ARM64_REG_Q4,
        Arm64Const.UC_ARM64_REG_Q5,
        Arm64Const.UC_ARM64_REG_Q6,
        Arm64Const.UC_ARM64_REG_Q7,
        Arm64Const.UC_ARM64_REG_Q8,
        Arm64Const.UC_ARM64_REG_Q9,
        Arm64Const.UC_ARM64_REG_Q10,
        Arm64Const.UC_ARM64_REG_Q11,
        Arm64Const.UC_ARM64_REG_Q12,
        Arm64Const.UC_ARM64_REG_Q13,
        Arm64Const.UC_ARM64_REG_Q14,
        Arm64Const.UC_ARM64_REG_Q15,

        Arm64Const.UC_ARM64_REG_Q16,
        Arm64Const.UC_ARM64_REG_Q17,
        Arm64Const.UC_ARM64_REG_Q18,
        Arm64Const.UC_ARM64_REG_Q19,
        Arm64Const.UC_ARM64_REG_Q20,
        Arm64Const.UC_ARM64_REG_Q21,
        Arm64Const.UC_ARM64_REG_Q22,
        Arm64Const.UC_ARM64_REG_Q23,
        Arm64Const.UC_ARM64_REG_Q24,
        Arm64Const.UC_ARM64_REG_Q25,
        Arm64Const.UC_ARM64_REG_Q26,
        Arm64Const.UC_ARM64_REG_Q27,
        Arm64Const.UC_ARM64_REG_Q28,
        Arm64Const.UC_ARM64_REG_Q29,
        Arm64Const.UC_ARM64_REG_Q30,
        Arm64Const.UC_ARM64_REG_Q31,

        Arm64Const.UC_ARM64_REG_LR,
        Arm64Const.UC_ARM64_REG_SP,
        Arm64Const.UC_ARM64_REG_PC,
        Arm64Const.UC_ARM64_REG_NZCV
    )

    private fun getRegArgs(emulator: Emulator<*>): IntArray {
        return if (emulator.is32Bit()) ARM_ARG_REGS else ARM64_ARG_REGS
    }

    @JvmStatic
    fun getAllRegisters(thumb: Boolean): IntArray {
        return if (thumb) THUMB_REGS else ARM_REGS
    }

    @JvmStatic
    fun getAll64Registers(): IntArray {
        return ARM64_REGS
    }

    private const val ALIGN_SIZE_BASE = 0x10

    @JvmStatic
    fun alignSize(size: Int): Int {
        return alignSize(size.toLong(), ALIGN_SIZE_BASE.toLong()).toInt()
    }

    @JvmStatic
    fun align(addr: Long, size: Long, alignment: Long): Alignment {
        var addrVar = addr
        var sizeVar = size
        val mask = -alignment
        var right = addrVar + sizeVar
        right = (right + alignment - 1) and mask
        addrVar = addrVar and mask
        sizeVar = right - addrVar
        sizeVar = (sizeVar + alignment - 1) and mask
        return Alignment(addrVar, sizeVar)
    }

    @JvmStatic
    fun alignSize(size: Long, align: Long): Long {
        return ((size - 1) / align + 1) * align
    }

    @JvmStatic
    fun assembleDetail(emulator: Emulator<*>, ins: Instruction, address: Long, thumb: Boolean, maxLengthLibraryName: Int): String {
        return assembleDetail(emulator, ins, address, thumb, false, maxLengthLibraryName)
    }

    private fun appendMemoryDetails32(emulator: Emulator<*>, ins: Instruction, opInfo: capstone.api.arm.OpInfo, thumb: Boolean, sb: StringBuilder) {
        val memory = emulator.getMemory()
        var mem: MemType? = null
        var addr: Long = -1
        val op = opInfo.getOperands()

        // ldr rx, [pc, #0xab] or ldr.w rx, [pc, #0xcd] based capstone.setDetail(Capstone.CS_OPT_ON);
        if (op.size == 2 &&
            op[0].getType() == capstone.Arm_const.ARM_OP_REG &&
            op[1].getType() == capstone.Arm_const.ARM_OP_MEM
        ) {
            mem = op[1].getValue().getMem()

            if (mem.getIndex() == 0 && mem.getScale() == 1 && mem.getLshift() == 0) {
                val base = VortexdbgPointer.register(emulator, ins.mapToUnicornReg(mem.getBase()))
                val base_value = base?.peer ?: 0L
                addr = base_value + mem.getDisp()
            }

            // ldr.w r0, [r2, r0, lsl #2]
            val shift: OpShift? = op[1].getShift()
            if (mem.getIndex() > 0 && mem.getScale() == 1 && mem.getLshift() == 0 && mem.getDisp() == 0 &&
                shift != null
            ) {
                val base = VortexdbgPointer.register(emulator, ins.mapToUnicornReg(mem.getBase()))
                val base_value = base?.peer ?: 0L
                val index = VortexdbgPointer.register(emulator, ins.mapToUnicornReg(mem.getIndex()))
                val index_value = if (index == null) 0 else index.peer.toInt()
                if (shift.getType() == capstone.Arm_const.ARM_OP_IMM) {
                    addr = base_value + (index_value.toLong() shl shift.getValue())
                } else if (shift.getType() == capstone.Arm_const.ARM_OP_INVALID) {
                    addr = base_value + index_value
                }
            }
        }

        // ldrb r0, [r1], #1
        if (op.size == 3 &&
            op[0].getType() == capstone.Arm_const.ARM_OP_REG &&
            op[1].getType() == capstone.Arm_const.ARM_OP_MEM &&
            op[2].getType() == capstone.Arm_const.ARM_OP_IMM
        ) {
            mem = op[1].getValue().getMem()
            if (mem.getIndex() == 0 && mem.getScale() == 1 && mem.getLshift() == 0) {
                val base = VortexdbgPointer.register(emulator, ins.mapToUnicornReg(mem.getBase()))
                addr = base?.peer ?: 0L
            }
        }
        if (addr != -1L) {
            if (ins.mapToUnicornReg(mem!!.getBase()) == ArmConst.UC_ARM_REG_PC) {
                addr += (if (thumb) 4 else 8)
            }
            val bytesRead = memAccessBytes(ins, 4)
            appendAddrValue(sb, addr, memory, emulator.is64Bit(), bytesRead)
            return
        }

        // ldrd r2, r1, [r5, #4]
        if ("ldrd" == ins.getMnemonic() && op.size == 3 &&
            op[0].getType() == capstone.Arm_const.ARM_OP_REG &&
            op[1].getType() == capstone.Arm_const.ARM_OP_REG &&
            op[2].getType() == capstone.Arm_const.ARM_OP_MEM
        ) {
            mem = op[2].getValue().getMem()
            if (mem.getIndex() == 0 && mem.getScale() == 1 && mem.getLshift() == 0) {
                val regId = ins.mapToUnicornReg(mem.getBase())
                val base = VortexdbgPointer.register(emulator, regId)
                val base_value = base?.peer ?: 0L
                addr = base_value + mem.getDisp()
                if (regId == ArmConst.UC_ARM_REG_PC) {
                    addr += (if (thumb) 4 else 8)
                }
                appendAddrValue(sb, addr, memory, emulator.is64Bit(), 4)
                appendAddrValue(sb, addr + emulator.getPointerSize(), memory, emulator.is64Bit(), 4)
            }
        }
    }

    private fun appendMemoryDetails64(emulator: Emulator<*>, ins: Instruction, opInfo: capstone.api.arm64.OpInfo, sb: StringBuilder) {
        val memory = emulator.getMemory()
        val mem: capstone.api.arm64.MemType
        var addr: Long = -1
        var bytesRead = 8
        val op = opInfo.getOperands()

        // str w9, [sp, #0xab] based capstone.setDetail(Capstone.CS_OPT_ON);
        if (op.size == 2 &&
            op[0].getType() == capstone.Arm64_const.ARM64_OP_REG &&
            op[1].getType() == capstone.Arm64_const.ARM64_OP_MEM
        ) {
            val regId = ins.mapToUnicornReg(op[0].getValue().getReg())
            if (regId >= Arm64Const.UC_ARM64_REG_W0 && regId <= Arm64Const.UC_ARM64_REG_W30) {
                bytesRead = 4
            }
            mem = op[1].getValue().getMem()

            if (mem.getIndex() == 0) {
                val base = VortexdbgPointer.register(emulator, ins.mapToUnicornReg(mem.getBase()))
                val base_value = base?.peer ?: 0L
                addr = base_value + mem.getDisp()
            }
        } else if (op.size == 3 &&
            op[0].getType() == capstone.Arm64_const.ARM64_OP_REG &&
            op[1].getType() == capstone.Arm64_const.ARM64_OP_MEM &&
            op[2].getType() == capstone.Arm64_const.ARM64_OP_IMM
        ) {
            // ldrb r0, [r1], #1
            val regId = ins.mapToUnicornReg(op[0].getValue().getReg())
            if (regId >= Arm64Const.UC_ARM64_REG_W0 && regId <= Arm64Const.UC_ARM64_REG_W30) {
                bytesRead = 4
            }
            mem = op[1].getValue().getMem()
            if (mem.getIndex() == 0) {
                val base = VortexdbgPointer.register(emulator, ins.mapToUnicornReg(mem.getBase()))
                addr = base?.peer ?: 0L
                addr += mem.getDisp()
            }
        }
        if (addr != -1L) {
            bytesRead = memAccessBytes(ins, bytesRead)
            appendAddrValue(sb, addr, memory, emulator.is64Bit(), bytesRead)
        }
    }

    @JvmStatic
    fun appendHex(builder: StringBuilder, value: Long, width: Int, placeholder: Char, reverse: Boolean) {
        builder.append("0x")
        val hex = java.lang.Long.toHexString(value)
        appendHex(builder, hex, width, placeholder, reverse)
    }

    @JvmStatic
    fun appendHex(builder: StringBuilder, str: String, width: Int, placeholder: Char, reverse: Boolean) {
        if (reverse) {
            builder.append(str)
            for (i in 0 until width - str.length) {
                builder.append(placeholder)
            }
        } else {
            for (i in 0 until width - str.length) {
                builder.append(placeholder)
            }
            builder.append(str)
        }
    }

    @JvmStatic
    fun assembleDetail(emulator: Emulator<*>, ins: Instruction, address: Long, thumb: Boolean, current: Boolean, maxLengthLibraryName: Int): String {
        val svcMemory = emulator.getSvcMemory()
        val region = svcMemory.findRegion(address)
        val memory = emulator.getMemory()
        val space = if (current) '*' else ' '
        val builder = StringBuilder()
        val module = if (region != null) null else memory.findModuleByAddress(address)
        if (module != null) {
            builder.append('[')
            appendHex(builder, module.name, maxLengthLibraryName, ' ', true)
            builder.append(space)
            appendHex(builder, address - module.base + (if (thumb) 1 else 0), java.lang.Long.toHexString(memory.getMaxSizeOfLibrary()).length, '0', false)
            builder.append(']').append(space)
        } else if (address >= svcMemory.getBase()) { // kernel
            builder.append('[')
            if (region == null) {
                appendHex(builder, "0x" + java.lang.Long.toHexString(address), maxLengthLibraryName, ' ', true)
            } else {
                appendHex(builder, region.getName().substring(0, Math.min(maxLengthLibraryName, region.getName().length)), maxLengthLibraryName, ' ', true)
            }
            builder.append(space)
            appendHex(builder, address - svcMemory.getBase() + (if (thumb) 1 else 0), java.lang.Long.toHexString(memory.getMaxSizeOfLibrary()).length, '0', false)
            builder.append(']').append(space)
        }
        builder.append("[")
        appendHex(builder, Hex.encodeHexString(ins.getBytes()), 8, ' ', true)
        builder.append("]")
        builder.append(space)
        appendHex(builder, ins.getAddress(), 8, '0', false)
        builder.append(":").append(space)
        builder.append('"').append(ins).append('"')

        var opInfo: capstone.api.arm.OpInfo? = null
        var opInfo64: capstone.api.arm64.OpInfo? = null
        if (ins.getOperands() is capstone.api.arm.OpInfo) {
            opInfo = ins.getOperands() as capstone.api.arm.OpInfo
        }
        if (ins.getOperands() is capstone.api.arm64.OpInfo) {
            opInfo64 = ins.getOperands() as capstone.api.arm64.OpInfo
        }
        if (current && (ins.getMnemonic().startsWith("ldr") || ins.getMnemonic().startsWith("str")) && opInfo != null) {
            appendMemoryDetails32(emulator, ins, opInfo, thumb, builder)
        }
        if (current && (ins.getMnemonic().startsWith("ldr") || ins.getMnemonic().startsWith("str")) && opInfo64 != null) {
            appendMemoryDetails64(emulator, ins, opInfo64, builder)
        }

        return builder.toString()
    }

    private fun memAccessBytes(ins: Instruction, defaultBytes: Int): Int {
        val mnemonic = ins.getMnemonic()
        if (mnemonic.startsWith("ldrb") || mnemonic.startsWith("strb")) {
            return 1
        }
        if (mnemonic.startsWith("ldrh") || mnemonic.startsWith("strh")) {
            return 2
        }
        return defaultBytes
    }

    private fun appendAddrValue(sb: StringBuilder, addr: Long, memory: Memory, is64Bit: Boolean, bytesRead: Int) {
        val mask = (-bytesRead).toLong()
        val pointer = memory.pointer(addr and mask)
        sb.append(" [0x").append(java.lang.Long.toHexString(addr)).append(']')
        try {
            if (is64Bit) {
                if (pointer != null) {
                    val value: Long
                    when (bytesRead) {
                        1 -> value = (pointer.getByte(0).toInt() and 0xff).toLong()
                        2 -> value = (pointer.getShort(0).toInt() and 0xffff).toLong()
                        4 -> value = pointer.getInt(0).toLong()
                        8 -> value = pointer.getLong(0)
                        else -> throw IllegalStateException("bytesRead=$bytesRead")
                    }
                    sb.append(" => 0x").append(java.lang.Long.toHexString(value))
                    if (value < 0) {
                        sb.append(" (-0x").append(java.lang.Long.toHexString(-value)).append(')')
                    } else if ((value and 0x7fffffff00000000L) == 0L) {
                        val iv = value.toInt()
                        if (iv < 0) {
                            sb.append(" (-0x").append(Integer.toHexString(-iv)).append(')')
                        }
                    }
                } else {
                    sb.append(" => null")
                }
            } else {
                val value: Int
                when (bytesRead) {
                    1 -> value = pointer.getByte(0).toInt() and 0xff
                    2 -> value = pointer.getShort(0).toInt() and 0xffff
                    4 -> value = pointer.getInt(0)
                    else -> throw IllegalStateException("bytesRead=$bytesRead")
                }
                sb.append(" => 0x").append(java.lang.Long.toHexString(value.toLong() and 0xffffffffL))
                if (value < 0) {
                    sb.append(" (-0x").append(Integer.toHexString(-value)).append(")")
                }
            }
        } catch (exception: RuntimeException) {
            sb.append(" => ").append(exception.message)
        }
    }

    private val log: Logger = LoggerFactory.getLogger(ARM::class.java)

    @JvmStatic
    fun initArgs(emulator: Emulator<*>, padding: Boolean, vararg arguments: Number) {
        val backend = emulator.getBackend()
        val memory = emulator.getMemory()

        val regArgs = getRegArgs(emulator)
        val argList: MutableList<Number> = ArrayList(arguments.size * 2)
        var regVector = Arm64Const.UC_ARM64_REG_Q0
        for (arg in arguments) {
            if (emulator.is64Bit()) {
                if (arg is Float) {
                    val buffer = ByteBuffer.allocate(16)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putFloat(arg)
                    emulator.getBackend().reg_write_vector(regVector++, buffer.array())
                    continue
                }
                if (arg is Double) {
                    val buffer = ByteBuffer.allocate(16)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putDouble(arg)
                    emulator.getBackend().reg_write_vector(regVector++, buffer.array())
                    continue
                }
                argList.add(arg)
                continue
            }
            if (arg is Long) {
                if (log.isDebugEnabled) {
                    log.debug("initLongArgs size={}, length={}", argList.size, regArgs.size, Exception("initArgs long=$arg"))
                }
                if (padding && argList.size % 2 != 0) {
                    argList.add(0)
                }
                val buffer = ByteBuffer.allocate(8)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                buffer.putLong(arg)
                buffer.flip()
                val v1 = buffer.getInt()
                val v2 = buffer.getInt()
                argList.add(v1)
                argList.add(v2)
            } else if (arg is Double) {
                if (log.isDebugEnabled) {
                    log.debug("initDoubleArgs size={}, length={}", argList.size, regArgs.size, Exception("initArgs double=$arg"))
                }
                if (padding && argList.size % 2 != 0) {
                    argList.add(0)
                }
                val buffer = ByteBuffer.allocate(8)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                buffer.putDouble(arg)
                buffer.flip()
                argList.add(buffer.getInt())
                argList.add(buffer.getInt())
            } else if (arg is Float) {
                if (log.isDebugEnabled) {
                    log.debug("initFloatArgs size={}, length={}", argList.size, regArgs.size, Exception("initArgs float=$arg"))
                }
                val buffer = ByteBuffer.allocate(4)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                buffer.putFloat(arg)
                buffer.flip()
                argList.add(buffer.getInt())
            } else {
                argList.add(arg)
            }
        }
        val args = Arguments(memory, argList.toTypedArray())

        val list: MutableList<Number> = ArrayList()
        if (args.args != null) {
            Collections.addAll(list, *args.args)
        }
        var i = 0
        while (list.isNotEmpty() && i < regArgs.size) {
            backend.reg_write(regArgs[i], list.removeAt(0))
            i++
        }
        Collections.reverse(list)
        if (list.size % 2 != 0) { // alignment sp
            memory.allocateStack(emulator.getPointerSize())
        }
        while (list.isNotEmpty()) {
            val number = list.removeAt(0)
            val pointer = memory.allocateStack(emulator.getPointerSize())
            assert(pointer != null)
            if (emulator.is64Bit()) {
                if ((pointer.peer % 8) != 0L) {
                    log.warn("init 64BitArgs pointer={}", pointer)
                }
                pointer.setLong(0, number.toLong())
            } else {
                if ((pointer.toUIntPeer() % 4) != 0L) {
                    log.warn("init 32BitArgs pointer={}", pointer)
                }
                pointer.setInt(0, number.toInt())
            }
        }
    }

    @JvmStatic
    fun adjust_ip(ip: VortexdbgPointer): VortexdbgPointer {
        var adjust = 4

        val thumb = (ip.peer and 1L) == 1L
        if (thumb) {
            /* Thumb instructions, the currently executing instruction could be
             * 2 or 4 bytes, so adjust appropriately.
             */
            val value = ip.share(-5).getInt(0)
            if ((value.toLong() and 0xe000f000L) != 0xe000f000L) {
                adjust = 2
            }
        }

        return ip.share((-adjust).toLong(), 0)
    }

}
