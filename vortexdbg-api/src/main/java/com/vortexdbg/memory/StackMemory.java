package com.vortexdbg.memory;

import com.vortexdbg.pointer.VortexdbgPointer;
import com.vortexdbg.serialize.Serializable;

public interface StackMemory extends Serializable {

    VortexdbgPointer writeStackString(String str);
    VortexdbgPointer writeStackBytes(byte[] data);

}
