package com.vortexdbg.linux.android.dvm.array;

import com.vortexdbg.Emulator;
import com.vortexdbg.linux.android.dvm.Array;
import com.vortexdbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

public interface PrimitiveArray<T> extends Array<T> {

    UnidbgPointer _GetArrayCritical(Emulator<?> emulator, Pointer isCopy);

    void _ReleaseArrayCritical(Pointer elems, int mode);

}
