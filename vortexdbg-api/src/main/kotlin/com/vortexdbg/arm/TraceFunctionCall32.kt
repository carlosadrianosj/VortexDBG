package com.vortexdbg.arm

import capstone.api.Instruction
import capstone.api.arm.OpInfo
import capstone.api.arm.Operand
import com.vortexdbg.Emulator
import com.vortexdbg.arm.backend.Backend
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.debugger.FunctionCallListener
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class TraceFunctionCall32(emulator: Emulator<*>, listener: FunctionCallListener) :
    TraceFunctionCall(emulator, listener) {

    override fun disassemble(address: Long, size: Int): Instruction? {
        val backend: Backend = emulator.getBackend()
        val thumb = ARM.isThumb(backend)
        return if (thumb) {
            disassembleThumb(address, size)
        } else {
            disassembleArm(address, size)
        }
    }

    private fun disassembleArm(address: Long, size: Int): Instruction? {
        if (size != 4) {
            throw IllegalStateException()
        }
        val code = emulator.getBackend().mem_read(address, 4)
        val buffer = ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN)
        val value = buffer.getInt()
        if ((value and ARM_BL_IMM_MASK) == ARM_BL_IMM ||
            (value and 0xfe000000.toInt()) == 0xfa000000.toInt()
        ) { // Encoding A2: BLX <label>
            val instructions = emulator.disassemble(address, code, false, 1)
            return instructions[0]
        }
        if ((value and ARM_BL_REG_MASK) == ARM_BL_REG) {
            val instructions = emulator.disassemble(address, code, false, 1)
            return instructions[0]
        }
        return null
    }

    private fun disassembleThumb(address: Long, size: Int): Instruction? {
        val code = emulator.getBackend().mem_read(address, size.toLong())
        if (size == 4) { // thumb2
            val buffer = ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN)
            val t1 = buffer.getShort().toInt() and 0xffff
            val t2 = buffer.getShort().toInt() and 0xffff
            val value = (t1 shl 16) or t2
            if ((value and THUMB_BL_IMM_MASK) == THUMB_BL_IMM) {
                val instructions = emulator.disassemble(address, code, true, 1)
                return instructions[0]
            }
        } else if (size == 2) {
            val buffer = ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN)
            val value = buffer.getShort()
            if ((value.toInt() and THUMB_BL_REG_MASK.toInt()) == THUMB_BL_REG.toInt()) {
                val instructions = emulator.disassemble(address, code, true, 1)
                return instructions[0]
            }
        } else {
            throw IllegalStateException()
        }
        return null
    }

    override fun onInstruction(instruction: Instruction) {
        val mnemonic = instruction.getMnemonic()
        val context = emulator.getContext<RegisterContext>()
        if ("bl" == mnemonic || "blx" == mnemonic) {
            val operands = instruction.getOperands() as OpInfo
            val operand: Operand = operands.getOperands()[0]
            val functionAddress: Long
            when (operand.getType()) {
                capstone.Arm_const.ARM_OP_IMM ->
                    functionAddress = operand.getValue().getImm().toLong()
                capstone.Arm_const.ARM_OP_REG ->
                    functionAddress = context.getIntByReg(instruction.mapToUnicornReg(operand.getValue().getReg())).toLong()
                else ->
                    throw UnsupportedOperationException("type=" + operand.getType())
            }
            val args = Array<Number>(4) { context.getIntArg(it) }
            pushFunction(instruction.getAddress(), functionAddress, instruction.getAddress() + instruction.getSize(), args)
        } else {
            throw UnsupportedOperationException()
        }
    }

    companion object {
        private const val ARM_BL_IMM_MASK = 0xf000000
        private const val ARM_BL_IMM = 0xb000000 // BL, BLX (immediate)

        private val ARM_BL_REG_MASK = (0xf000000f.toInt()).inv()
        private const val ARM_BL_REG = 0x12fff30 // BLX<c> <Rm>

        private val THUMB_BL_IMM_MASK = 0xf800c000.toInt()
        private val THUMB_BL_IMM = 0xf000c000.toInt() // BL, BLX (immediate)

        private val THUMB_BL_REG_MASK = (0x78.inv()).toShort()
        private val THUMB_BL_REG: Short = 0x4780 // BLX<c> <Rm>
    }

}
