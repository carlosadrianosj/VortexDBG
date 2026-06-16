package com.vortexdbg.debugger.gdb;

import com.vortexdbg.Emulator;

interface GdbStubCommand {

    boolean processCommand(Emulator<?> emulator, GdbStub stub, String command);

}
