package com.vortexdbg.ios;

import com.vortexdbg.Emulator;
import com.vortexdbg.Module;
import com.vortexdbg.arm.backend.HypervisorFactory;
import com.vortexdbg.file.FileResult;
import com.vortexdbg.file.IOResolver;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.ios.file.SimpleFileIO;
import com.vortexdbg.memory.Memory;

import java.io.File;
import java.io.IOException;

public class JToolTest {

    public static void main(String[] args) throws IOException {
        DarwinEmulatorBuilder builder = DarwinEmulatorBuilder.for64Bit();
        builder.addBackendFactory(new HypervisorFactory(true));
        Emulator<DarwinFileIO> emulator = builder.build();
        Memory memory = emulator.getMemory();
        memory.setLibraryResolver(new DarwinResolver());
        emulator.getSyscallHandler().setVerbose(false);

        final File jtool = new File("unidbg-ios/src/test/resources/example_binaries/jtool_osx");
        IOResolver<DarwinFileIO> resolver = (emulator1, pathname, oflags) -> {
            if ("test_executable".equals(pathname)) {
                return FileResult.success(new SimpleFileIO(oflags, jtool, pathname));
            }
            return null;
        };
        emulator.getSyscallHandler().addIOResolver(resolver);

        Module module = emulator.loadLibrary(jtool);
        long start = System.currentTimeMillis();
        int ret = module.callEntry(emulator, "-v", "-l", "--sig", "test_executable");
        System.err.println("jtool backend=" + emulator.getBackend() + ", ret=0x" + Integer.toHexString(ret) + ", offset=" + (System.currentTimeMillis() - start) + "ms");
    }

}
