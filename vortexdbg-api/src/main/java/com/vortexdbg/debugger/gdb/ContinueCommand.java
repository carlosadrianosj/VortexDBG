package com.vortexdbg.debugger.gdb;

import com.vortexdbg.Emulator;

class ContinueCommand implements GdbStubCommand {

    @Override
    public boolean processCommand(Emulator<?> emulator, GdbStub stub, String command) {
        stub.resumeRun();
        stub.send("+");
        return true;
    }

}
