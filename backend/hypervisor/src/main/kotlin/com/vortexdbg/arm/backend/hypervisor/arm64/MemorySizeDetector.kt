package com.vortexdbg.arm.backend.hypervisor.arm64

import capstone.api.Instruction

interface MemorySizeDetector {

    fun detectReadSize(insn: Instruction): Int

    fun detectWriteSize(insn: Instruction): Int

}
