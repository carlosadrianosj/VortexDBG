package com.vortexdbg.arm.context;

import com.vortexdbg.pointer.VortexdbgPointer;

public interface Arm32RegisterContext extends RegisterContext {

    long getR0Long();

    long getR1Long();

    long getR2Long();

    long getR3Long();

    long getR4Long();

    long getR5Long();

    long getR6Long();

    long getR7Long();

    long getR8Long();

    long getR9Long();

    long getR10Long();

    long getR11Long();

    long getR12Long();

    int getR0Int();

    int getR1Int();

    int getR2Int();

    int getR3Int();

    int getR4Int();

    int getR5Int();

    int getR6Int();

    int getR7Int();

    int getR8Int();

    int getR9Int();

    int getR10Int();

    int getR11Int();

    int getR12Int();

    VortexdbgPointer getR0Pointer();

    VortexdbgPointer getR1Pointer();

    VortexdbgPointer getR2Pointer();

    VortexdbgPointer getR3Pointer();

    VortexdbgPointer getR4Pointer();

    VortexdbgPointer getR5Pointer();

    VortexdbgPointer getR6Pointer();

    VortexdbgPointer getR7Pointer();

    VortexdbgPointer getR8Pointer();

    VortexdbgPointer getR9Pointer();

    VortexdbgPointer getR10Pointer();

    VortexdbgPointer getR11Pointer();

    VortexdbgPointer getR12Pointer();

}
