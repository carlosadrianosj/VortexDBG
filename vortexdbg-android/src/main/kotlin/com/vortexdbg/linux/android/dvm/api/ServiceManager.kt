package com.vortexdbg.linux.android.dvm.api

import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM

open class ServiceManager(vm: VM, value: String) : DvmObject<String>(vm.resolveClass("android/os/IServiceManager"), value) {

    open fun getService(vm: VM, serviceName: String): SystemService {
        return SystemService(vm, serviceName)
    }
}
