package com.vortexdbg.arm

import capstone.api.Instruction
import capstone.api.arm64.OpInfo
import capstone.api.arm64.Operand
import com.vortexdbg.Emulator
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.debugger.FunctionCallListener
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class TraceFunctionCall64(emulator: Emulator<*>, listener: FunctionCallListener) :
    TraceFunctionCall(emulator, listener) {

    override fun disassemble(address: Long, size: Int): Instruction? {
        if (size != 4) {
            throw IllegalStateException()
        }
        val code = emulator.getBackend().mem_read(address, 4)
        val buffer = ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN)
        val value = buffer.getInt()
        if ((value and BL_MASK) == BL) {
            val instructions = emulator.disassemble(address, code, false, 1)
            return instructions[0]
        }
        if ((value and BLR_MASK) == BLR) {
            val instructions = emulator.disassemble(address, code, false, 1)
            return instructions[0]
        }
        return null
    }

    override fun onInstruction(instruction: Instruction) {
        val mnemonic = instruction.getMnemonic()
        val context = emulator.getContext<RegisterContext>()
        if ("bl" == mnemonic || "blr" == mnemonic) {
            val operands = instruction.getOperands() as OpInfo
            val operand: Operand = operands.getOperands()[0]
            val functionAddress: Long
            when (operand.getType()) {
                capstone.Arm64_const.ARM64_OP_IMM ->
                    functionAddress = operand.getValue().getImm()
                capstone.Arm64_const.ARM64_OP_REG ->
                    functionAddress = context.getLongByReg(instruction.mapToUnicornReg(operand.getValue().getReg()))
                else ->
                    throw UnsupportedOperationException("type=" + operand.getType())
            }
            val args = Array<Number>(8) { context.getLongArg(it) }
            pushFunction(instruction.getAddress(), functionAddress, instruction.getAddress() + instruction.getSize(), args)
        } else {
            throw UnsupportedOperationException()
        }
    }

    companion object {
        private val BL_MASK = (0x3ffffff).inv()
        private val BL = 0x94000000.toInt() // BL <label>

        private val BLR_MASK = (0x3e0).inv()
        private val BLR = 0xd63f0000.toInt() // BLR <Xn>
    }

}
