package com.vortexdbg

import capstone.api.Instruction
import com.vortexdbg.arm.Cpsr
import com.vortexdbg.arm.backend.Backend
import unicorn.Arm64Const
import unicorn.ArmConst

import java.util.Locale

internal class RegAccessPrinter(
    private val address: Long,
    private val instruction: Instruction,
    private val accessRegs: ShortArray,
    private var forWriteRegs: Boolean
) {

    fun print(emulator: Emulator<*>, backend: Backend, builder: StringBuilder, address: Long) {
        if (this.address != address) {
            return
        }
        for (reg in accessRegs) {
            val regId = instruction.mapToUnicornReg(reg.toInt())
            if (emulator.is32Bit()) {
                if ((regId >= ArmConst.UC_ARM_REG_R0 && regId <= ArmConst.UC_ARM_REG_R12) ||
                    regId == ArmConst.UC_ARM_REG_LR || regId == ArmConst.UC_ARM_REG_SP ||
                    regId == ArmConst.UC_ARM_REG_CPSR) {
                    if (forWriteRegs) {
                        builder.append(" =>")
                        forWriteRegs = false
                    }
                    if (regId == ArmConst.UC_ARM_REG_CPSR) {
                        val cpsr = Cpsr.getArm(backend)
                        builder.append(String.format(Locale.US, " cpsr: N=%d, Z=%d, C=%d, V=%d",
                            if (cpsr.isNegative()) 1 else 0,
                            if (cpsr.isZero()) 1 else 0,
                            if (cpsr.hasCarry()) 1 else 0,
                            if (cpsr.isOverflow()) 1 else 0))
                    } else {
                        val value = backend.reg_read(regId).toInt()
                        builder.append(' ').append(instruction.regName(reg.toInt())).append("=0x").append(java.lang.Long.toHexString(value.toLong() and 0xffffffffL))
                    }
                }
            } else {
                if ((regId >= Arm64Const.UC_ARM64_REG_X0 && regId <= Arm64Const.UC_ARM64_REG_X28) ||
                    (regId >= Arm64Const.UC_ARM64_REG_X29 && regId <= Arm64Const.UC_ARM64_REG_SP)) {
                    if (forWriteRegs) {
                        builder.append(" =>")
                        forWriteRegs = false
                    }
                    if (regId == Arm64Const.UC_ARM64_REG_NZCV) {
                        val cpsr = Cpsr.getArm64(backend)
                        if (cpsr.isA32()) {
                            builder.append(String.format(Locale.US, " cpsr: N=%d, Z=%d, C=%d, V=%d",
                                if (cpsr.isNegative()) 1 else 0,
                                if (cpsr.isZero()) 1 else 0,
                                if (cpsr.hasCarry()) 1 else 0,
                                if (cpsr.isOverflow()) 1 else 0))
                        } else {
                            builder.append(String.format(Locale.US, " nzcv: N=%d, Z=%d, C=%d, V=%d",
                                if (cpsr.isNegative()) 1 else 0,
                                if (cpsr.isZero()) 1 else 0,
                                if (cpsr.hasCarry()) 1 else 0,
                                if (cpsr.isOverflow()) 1 else 0))
                        }
                    } else {
                        val value = backend.reg_read(regId).toLong()
                        builder.append(' ').append(instruction.regName(reg.toInt())).append("=0x").append(java.lang.Long.toHexString(value))
                    }
                } else if (regId >= Arm64Const.UC_ARM64_REG_W0 && regId <= Arm64Const.UC_ARM64_REG_W30) {
                    if (forWriteRegs) {
                        builder.append(" =>")
                        forWriteRegs = false
                    }
                    val value = backend.reg_read(regId).toInt()
                    builder.append(' ').append(instruction.regName(reg.toInt())).append("=0x").append(java.lang.Long.toHexString(value.toLong() and 0xffffffffL))
                }
            }
        }
    }

}
