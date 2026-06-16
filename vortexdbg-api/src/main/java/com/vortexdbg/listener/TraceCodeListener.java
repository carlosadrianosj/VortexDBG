package com.vortexdbg.listener;

import capstone.api.Instruction;
import com.vortexdbg.Emulator;

public interface TraceCodeListener {

    void onInstruction(Emulator<?> emulator, long address, Instruction insn);

}
