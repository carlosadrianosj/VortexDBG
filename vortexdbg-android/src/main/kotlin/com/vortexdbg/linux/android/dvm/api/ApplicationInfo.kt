package com.vortexdbg.linux.android.dvm.api

import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM

open class ApplicationInfo(vm: VM) : DvmObject<Any?>(vm.resolveClass("android/content/pm/ApplicationInfo"), null)
