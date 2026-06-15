package com.vortexdbg.linux.android.dvm.array;

import com.vortexdbg.linux.android.dvm.Array;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.DvmObject;

abstract class BaseArray<T> extends DvmObject<T> implements Array<T> {

    BaseArray(DvmClass objectType, T value) {
        super(objectType, value);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

}
