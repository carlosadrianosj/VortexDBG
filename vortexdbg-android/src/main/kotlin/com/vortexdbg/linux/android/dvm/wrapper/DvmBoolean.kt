package com.vortexdbg.linux.android.dvm.wrapper

import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM

class DvmBoolean private constructor(vm: VM, value: Boolean) : DvmObject<Boolean>(vm.resolveClass("java/lang/Boolean"), value) {

    override fun toString(): String {
        return java.lang.Boolean.toString(value)
    }

    companion object {
        @JvmStatic
        @Suppress("unused")
        fun valueOf(vm: VM, b: Boolean): DvmBoolean {
            return DvmBoolean(vm, b)
        }
    }
}
