package com.vortexdbg.spi

import capstone.api.Instruction
import com.vortexdbg.arm.InstructionVisitor

import java.io.PrintStream

/** Capstone-backed disassembler for ARM/ARM64 guest code. */
interface ArmDisassembler {

    fun printAssemble(out: PrintStream, address: Long, size: Int, maxLengthLibraryName: Int, visitor: InstructionVisitor): Array<Instruction>
    fun disassemble(address: Long, size: Int, count: Long): Array<Instruction>
    fun disassemble(address: Long, code: ByteArray, thumb: Boolean, count: Long): Array<Instruction>

}
