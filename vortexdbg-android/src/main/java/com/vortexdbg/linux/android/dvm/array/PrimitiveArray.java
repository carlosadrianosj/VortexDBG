package com.vortexdbg.linux.android.dvm.array;

import com.vortexdbg.Emulator;
import com.vortexdbg.linux.android.dvm.Array;
import com.vortexdbg.pointer.VortexdbgPointer;
import com.sun.jna.Pointer;

public interface PrimitiveArray<T> extends Array<T> {

    VortexdbgPointer _GetArrayCritical(Emulator<?> emulator, Pointer isCopy);

    void _ReleaseArrayCritical(Pointer elems, int mode);

}
