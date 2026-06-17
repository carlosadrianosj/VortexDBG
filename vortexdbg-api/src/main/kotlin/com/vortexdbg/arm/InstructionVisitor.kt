package com.vortexdbg.arm

import capstone.api.Instruction

interface InstructionVisitor {

    fun visit(builder: StringBuilder, ins: Instruction)

    fun visitLast(builder: StringBuilder)
}
