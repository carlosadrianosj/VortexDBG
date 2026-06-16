package com.vortexdbg.android.ida;

import com.vortexdbg.arm.backend.BackendFactory;
import com.vortexdbg.arm.backend.HypervisorFactory;
import com.vortexdbg.file.linux.AndroidFileIO;
import com.vortexdbg.linux.android.AndroidARM64Emulator;
import com.vortexdbg.memory.SvcMemory;
import com.vortexdbg.unix.UnixSyscallHandler;

import java.io.File;
import java.util.Collections;

class MyAndroidARM64Emulator extends AndroidARM64Emulator {

    public MyAndroidARM64Emulator(File executable) {
        super(executable.getName(),
                new File("target/rootfs/ida"),
                Collections.<BackendFactory>singleton(new HypervisorFactory(true)));
    }

    @Override
    protected UnixSyscallHandler<AndroidFileIO> createSyscallHandler(SvcMemory svcMemory) {
        return new MyARM64SyscallHandler(svcMemory);
    }

}
