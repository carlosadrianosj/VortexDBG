package com.vortexdbg.memory;

import com.vortexdbg.pointer.UnidbgPointer;
import com.vortexdbg.serialize.Serializable;

public interface StackMemory extends Serializable {

    UnidbgPointer writeStackString(String str);
    UnidbgPointer writeStackBytes(byte[] data);

}
