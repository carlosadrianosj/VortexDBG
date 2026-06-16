package com.vortexdbg.spi

import capstone.api.Instruction
import com.vortexdbg.arm.InstructionVisitor

import java.io.PrintStream

/**
 * disassembler
 * Created by zhkl0228 on 2017/5/9.
 */

interface ArmDisassembler {

    fun printAssemble(out: PrintStream, address: Long, size: Int, maxLengthLibraryName: Int, visitor: InstructionVisitor): Array<Instruction>
    fun disassemble(address: Long, size: Int, count: Long): Array<Instruction>
    fun disassemble(address: Long, code: ByteArray, thumb: Boolean, count: Long): Array<Instruction>

}
