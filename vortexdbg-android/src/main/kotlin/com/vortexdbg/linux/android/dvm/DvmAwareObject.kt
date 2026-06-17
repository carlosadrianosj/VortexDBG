package com.vortexdbg.linux.android.dvm

import com.vortexdbg.AndroidEmulator

interface DvmAwareObject {

    fun initializeDvm(emulator: AndroidEmulator, vm: VM, `object`: DvmObject<*>)

}
