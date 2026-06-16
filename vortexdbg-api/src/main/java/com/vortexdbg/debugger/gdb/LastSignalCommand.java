package com.vortexdbg.debugger.gdb;

import com.vortexdbg.Emulator;

class LastSignalCommand implements GdbStubCommand {

    @Override
    public boolean processCommand(Emulator<?> emulator, GdbStub stub, String command) {
        stub.makePacketAndSend("S" + GdbStub.SIGTRAP);
        return true;
    }

}
