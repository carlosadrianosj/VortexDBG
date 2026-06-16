package com.vortexdbg.linux.android.dvm.wrapper;

import com.vortexdbg.linux.android.dvm.DvmObject;
import com.vortexdbg.linux.android.dvm.VM;

public class DvmInteger extends DvmObject<Integer> {

    @SuppressWarnings("unused")
    public static DvmInteger valueOf(VM vm, int i) {
        return new DvmInteger(vm, i);
    }

    private DvmInteger(VM vm, Integer value) {
        super(vm.resolveClass("java/lang/Integer"), value);
    }
}
