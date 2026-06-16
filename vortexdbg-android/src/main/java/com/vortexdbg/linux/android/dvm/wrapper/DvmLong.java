package com.vortexdbg.linux.android.dvm.wrapper;

import com.vortexdbg.linux.android.dvm.DvmObject;
import com.vortexdbg.linux.android.dvm.VM;

public class DvmLong extends DvmObject<Long> {

    public static DvmLong valueOf(VM vm, long i) {
        return new DvmLong(vm, i);
    }

    private DvmLong(VM vm, Long value) {
        super(vm.resolveClass("java/lang/Long"), value);
    }
}
