package com.vortexdbg.debugger.gdb;

import com.vortexdbg.Emulator;

class DetachCommand implements GdbStubCommand {

    @Override
    public boolean processCommand(Emulator<?> emulator, GdbStub stub, String command) {
        stub.makePacketAndSend("OK");
        stub.detachServer();
        return true;
    }

}
