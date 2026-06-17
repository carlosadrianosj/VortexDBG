package com.vortexdbg.linux.android.dvm.api

import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM

open class PackageInfo(vm: VM, packageName: String, private val flags: Int) : DvmObject<String>(vm.resolveClass("android/content/pm/PackageInfo"), packageName) {

    open fun getPackageName(): String {
        return getValue()
    }

    open fun getFlags(): Int {
        return flags
    }
}
