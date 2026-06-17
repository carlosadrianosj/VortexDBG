package com.vortexdbg.linux.android.dvm.api

import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM

import java.util.Properties

open class Bundle(vm: VM, properties: Properties) : DvmObject<Properties>(vm.resolveClass("android/os/Bundle"), properties) {

    open fun getInt(key: String): Int {
        val value = this.value.getProperty(key) ?: throw BackendException("key=$key")
        return Integer.parseInt(value, 16)
    }
}
