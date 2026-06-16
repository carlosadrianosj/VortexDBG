package com.vortexdbg.linux.android.dvm;

import com.vortexdbg.Emulator;
import com.vortexdbg.pointer.VortexdbgPointer;

public abstract class ArmVarArg extends VarArg {

    static VarArg create(Emulator<?> emulator, BaseVM vm, DvmMethod method) {
        return emulator.is64Bit() ? new ArmVarArg64(emulator, vm, method) : new ArmVarArg32(emulator, vm, method);
    }

    protected final Emulator<?> emulator;

    protected ArmVarArg(Emulator<?> emulator, BaseVM vm, DvmMethod method) {
        super(vm, method);
        this.emulator = emulator;
    }

    private static final int REG_OFFSET = 3;

    protected final VortexdbgPointer getArg(int index) {
        return emulator.getContext().getPointerArg(REG_OFFSET + index);
    }

    protected final int getInt(int index) {
        VortexdbgPointer ptr = getArg(index);
        return ptr == null ? 0 : ptr.toIntPeer();
    }

}
