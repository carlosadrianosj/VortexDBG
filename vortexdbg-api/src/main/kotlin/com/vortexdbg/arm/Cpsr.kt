package com.vortexdbg.arm

import com.vortexdbg.arm.backend.Backend
import unicorn.Arm64Const
import unicorn.ArmConst

class Cpsr private constructor(private val backend: Backend, private val regId: Int) {

    private fun setBit(offset: Int) {
        val mask = 1 shl offset
        regValue = regValue or mask
        backend.reg_write(regId, regValue)
    }

    private fun clearBit(offset: Int) {
        val mask = (1 shl offset).inv()
        regValue = regValue and mask
        backend.reg_write(regId, regValue)
    }

    private var regValue: Int = backend.reg_read(regId).toInt()

    fun getValue(): Int {
        return regValue
    }

    fun isA32(): Boolean {
        return hasBit(regValue, A32_BIT)
    }

    fun isThumb(): Boolean {
        return hasBit(regValue, THUMB_BIT)
    }

    fun isNegative(): Boolean {
        return hasBit(regValue, NEGATIVE_BIT)
    }

    fun setNegative(on: Boolean) {
        if (on) {
            setBit(NEGATIVE_BIT)
        } else {
            clearBit(NEGATIVE_BIT)
        }
    }

    fun isZero(): Boolean {
        return hasBit(regValue, ZERO_BIT)
    }

    fun setZero(on: Boolean) {
        if (on) {
            setBit(ZERO_BIT)
        } else {
            clearBit(ZERO_BIT)
        }
    }

    /**
     * 进位或借位
     */
    fun hasCarry(): Boolean {
        return hasBit(regValue, CARRY_BIT)
    }

    fun setCarry(on: Boolean) {
        if (on) {
            setBit(CARRY_BIT)
        } else {
            clearBit(CARRY_BIT)
        }
    }

    fun isOverflow(): Boolean {
        return hasBit(regValue, OVERFLOW_BIT)
    }

    fun setOverflow(on: Boolean) {
        if (on) {
            setBit(OVERFLOW_BIT)
        } else {
            clearBit(OVERFLOW_BIT)
        }
    }

    fun getMode(): Int {
        return regValue and MODE_MASK
    }

    fun getEL(): Int {
        return (regValue shr 2) and 3
    }

    fun switchUserMode() {
        regValue = regValue and MODE_MASK.inv()
        regValue = regValue or ARMEmulator.USR_MODE
        backend.reg_write(regId, regValue)
    }

    companion object {
        private fun hasBit(value: Int, offset: Int): Boolean {
            return ((value shr offset) and 1) == 1
        }

        @JvmStatic
        fun getArm(backend: Backend): Cpsr {
            return Cpsr(backend, ArmConst.UC_ARM_REG_CPSR)
        }

        @JvmStatic
        fun getArm64(backend: Backend): Cpsr {
            return Cpsr(backend, Arm64Const.UC_ARM64_REG_NZCV)
        }

        private const val A32_BIT = 4
        private const val THUMB_BIT = 5
        private const val NEGATIVE_BIT = 31
        private const val ZERO_BIT = 30
        private const val CARRY_BIT = 29
        private const val OVERFLOW_BIT = 28
        private const val MODE_MASK = 0x1f
    }

}
