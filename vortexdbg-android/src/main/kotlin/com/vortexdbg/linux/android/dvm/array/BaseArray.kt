package com.vortexdbg.linux.android.dvm.array

import com.vortexdbg.linux.android.dvm.Array
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmObject

abstract class BaseArray<T>(objectType: DvmClass?, value: T) : DvmObject<T>(objectType, value), Array<T> {

    override fun toString(): String {
        return java.lang.String.valueOf(value)
    }

}
