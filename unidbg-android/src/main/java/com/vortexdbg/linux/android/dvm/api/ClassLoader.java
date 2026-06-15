package com.vortexdbg.linux.android.dvm.api;

import com.vortexdbg.linux.android.dvm.DvmObject;
import com.vortexdbg.linux.android.dvm.VM;

public class ClassLoader extends DvmObject<String> {

    public ClassLoader(VM vm, String value) {
        super(vm.resolveClass("dalvik/system/PathClassLoader"), value);
    }

}
