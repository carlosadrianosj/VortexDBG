package com.vortexdbg.memory;

import com.vortexdbg.Svc;
import com.vortexdbg.pointer.VortexdbgPointer;

public interface SvcMemory extends StackMemory {

    VortexdbgPointer allocate(int size, String label);

    VortexdbgPointer allocateSymbolName(String name);

    VortexdbgPointer registerSvc(Svc svc);

    Svc getSvc(int svcNumber);

    MemRegion findRegion(long addr);

    long getBase();
    int getSize();

}
