package com.vortexdbg.linux.android.dvm.api

import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM

open class ClassLoader(vm: VM, value: String) : DvmObject<String>(vm.resolveClass("dalvik/system/PathClassLoader"), value)
