package com.vortexdbg.android.ida;

import com.vortexdbg.arm.backend.BackendFactory;
import com.vortexdbg.arm.backend.DynarmicFactory;
import com.vortexdbg.file.linux.AndroidFileIO;
import com.vortexdbg.linux.android.AndroidARMEmulator;
import com.vortexdbg.memory.SvcMemory;
import com.vortexdbg.unix.UnixSyscallHandler;

import java.io.File;
import java.util.Collections;

class MyAndroidARMEmulator extends AndroidARMEmulator {

    public MyAndroidARMEmulator(File executable) {
        super(executable.getName(),
                new File("target/rootfs/ida"),
                Collections.<BackendFactory>singleton(new DynarmicFactory(true)));
    }

    @Override
    protected UnixSyscallHandler<AndroidFileIO> createSyscallHandler(SvcMemory svcMemory) {
        return new MyARMSyscallHandler(svcMemory);
    }

}
