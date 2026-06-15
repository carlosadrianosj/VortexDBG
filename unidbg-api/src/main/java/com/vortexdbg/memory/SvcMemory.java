package com.vortexdbg.memory;

import com.vortexdbg.Svc;
import com.vortexdbg.pointer.UnidbgPointer;

public interface SvcMemory extends StackMemory {

    UnidbgPointer allocate(int size, String label);

    UnidbgPointer allocateSymbolName(String name);

    UnidbgPointer registerSvc(Svc svc);

    Svc getSvc(int svcNumber);

    MemRegion findRegion(long addr);

    long getBase();
    int getSize();

}
