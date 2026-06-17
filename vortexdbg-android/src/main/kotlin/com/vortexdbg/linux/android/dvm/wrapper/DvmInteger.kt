package com.vortexdbg.linux.android.dvm.wrapper

import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM

class DvmInteger private constructor(vm: VM, value: Int) : DvmObject<Int>(vm.resolveClass("java/lang/Integer"), value) {

    companion object {
        @JvmStatic
        @Suppress("unused")
        fun valueOf(vm: VM, i: Int): DvmInteger {
            return DvmInteger(vm, i)
        }
    }
}
