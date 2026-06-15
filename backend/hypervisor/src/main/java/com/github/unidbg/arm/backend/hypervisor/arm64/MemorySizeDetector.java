package com.github.unidbg.arm.backend.hypervisor.arm64;

import capstone.api.Instruction;

public interface MemorySizeDetector {

    int detectReadSize(Instruction insn);

    int detectWriteSize(Instruction insn);

}
