package com.vortexdbg.ios;

import com.vortexdbg.Emulator;
import com.vortexdbg.Module;
import com.vortexdbg.arm.backend.DynarmicFactory;
import com.vortexdbg.arm.backend.HypervisorFactory;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.hook.DispatchAsyncCallback;
import com.vortexdbg.hook.HookLoader;
import com.vortexdbg.ios.ipa.SymbolResolver;
import com.vortexdbg.memory.Memory;

import java.io.File;
import java.io.IOException;

public class SwiftTest {

    public static void main(String[] args) throws IOException {
        DarwinEmulatorBuilder builder = DarwinEmulatorBuilder.for64Bit();
        builder.addBackendFactory(new HypervisorFactory(true));
        builder.addBackendFactory(new DynarmicFactory(true));
        final Emulator<DarwinFileIO> emulator = builder.build();

        Memory memory = emulator.getMemory();
        memory.addHookListener(new SymbolResolver(emulator));
        memory.setLibraryResolver(new DarwinResolver().setOverride());
        emulator.getSyscallHandler().setVerbose(false);
        emulator.getSyscallHandler().setEnableThreadDispatcher(true);

        Module module = emulator.loadLibrary(new File("unidbg-ios/src/test/resources/example_binaries/swift_test"));
        HookLoader.load(emulator).hookDispatchAsync((emulator1, dq, fun, is_barrier_async) -> {
            System.out.println("canDispatch dq=" + dq + ", fun=" + fun + ", is_barrier_async=" + is_barrier_async);
            return DispatchAsyncCallback.Result.direct_run;
        });
        long start = System.currentTimeMillis();
        int ret = module.callEntry(emulator);
        System.err.println("testSwift backend=" + emulator.getBackend() + ", ret=0x" + Integer.toHexString(ret) + ", offset=" + (System.currentTimeMillis() - start) + "ms");
    }

}
