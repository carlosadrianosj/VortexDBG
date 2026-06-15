package com.vortexdbg.linux.android.dvm.api;

import com.vortexdbg.linux.android.dvm.DvmObject;
import com.vortexdbg.linux.android.dvm.VM;

public class AssetManager extends DvmObject<String> {

    public AssetManager(VM vm, String value) {
        super(vm.resolveClass("android/content/res/AssetManager"), value);
    }

}
