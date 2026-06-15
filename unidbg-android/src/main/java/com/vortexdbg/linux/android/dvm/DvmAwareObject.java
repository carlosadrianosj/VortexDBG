package com.vortexdbg.linux.android.dvm;

import com.vortexdbg.AndroidEmulator;

public interface DvmAwareObject {

    void initializeDvm(AndroidEmulator emulator, VM vm, DvmObject<?> object);

}
