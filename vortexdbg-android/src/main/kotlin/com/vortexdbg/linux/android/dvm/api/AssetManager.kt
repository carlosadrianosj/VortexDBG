package com.vortexdbg.linux.android.dvm.api

import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM

open class AssetManager(vm: VM, value: String) : DvmObject<String>(vm.resolveClass("android/content/res/AssetManager"), value)
