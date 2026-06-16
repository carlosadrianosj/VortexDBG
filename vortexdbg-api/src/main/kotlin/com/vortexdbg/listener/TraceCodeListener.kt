package com.vortexdbg.listener

import capstone.api.Instruction
import com.vortexdbg.Emulator

interface TraceCodeListener {

    fun onInstruction(emulator: Emulator<*>, address: Long, insn: Instruction)

}
