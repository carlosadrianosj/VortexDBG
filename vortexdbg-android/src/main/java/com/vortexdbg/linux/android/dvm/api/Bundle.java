package com.vortexdbg.linux.android.dvm.api;

import com.vortexdbg.arm.backend.BackendException;
import com.vortexdbg.linux.android.dvm.DvmObject;
import com.vortexdbg.linux.android.dvm.VM;

import java.util.Properties;

public class Bundle extends DvmObject<Properties> {

    public Bundle(VM vm, Properties properties) {
        super(vm.resolveClass("android/os/Bundle"), properties);
    }

    public int getInt(String key) {
        String value = super.value.getProperty(key);
        if (value == null) {
            throw new BackendException("key=" + key);
        }

        return Integer.parseInt(value, 16);
    }
}
