package com.vortexdbg.linux.android.dvm

open class StringObject(vm: VM, value: String) : DvmObject<String>(vm.resolveClass("java/lang/String"), value) {

    init {
        @Suppress("SENSELESS_COMPARISON")
        if (value == null) {
            throw NullPointerException()
        }
    }

    override fun toString(): String {
        return "\"" + value + "\""
    }
}
