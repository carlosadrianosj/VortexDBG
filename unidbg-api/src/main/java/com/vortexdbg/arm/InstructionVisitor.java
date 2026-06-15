package com.vortexdbg.arm;

import capstone.api.Instruction;

public interface InstructionVisitor {

    void visit(StringBuilder builder, Instruction ins);

    void visitLast(StringBuilder builder);
}
