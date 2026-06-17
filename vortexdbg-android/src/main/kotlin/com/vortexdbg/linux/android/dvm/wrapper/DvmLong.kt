package com.vortexdbg.linux.android.dvm.wrapper

import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM

class DvmLong private constructor(vm: VM, value: Long) : DvmObject<Long>(vm.resolveClass("java/lang/Long"), value) {

    companion object {
        @JvmStatic
        fun valueOf(vm: VM, i: Long): DvmLong {
            return DvmLong(vm, i)
        }
    }
}
