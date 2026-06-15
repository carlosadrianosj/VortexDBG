package com.vortexdbg.debugger.gdb;

import com.vortexdbg.Emulator;

class StepCommand implements GdbStubCommand {

    @Override
    public boolean processCommand(Emulator<?> emulator, GdbStub stub, String command) {
        stub.singleStep();
        stub.makePacketAndSend("OK");
        return true;
    }

}
