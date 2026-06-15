package com.vortexdbg.ios;

import com.vortexdbg.LibraryResolver;
import com.vortexdbg.Module;
import com.vortexdbg.arm.ARMEmulator;
import com.vortexdbg.file.ios.DarwinFileIO;

import java.io.File;

public class MountTest extends EmulatorTest<ARMEmulator<DarwinFileIO>> {

    @Override
    protected LibraryResolver createLibraryResolver() {
        return new DarwinResolver();
    }

    @Override
    protected ARMEmulator<DarwinFileIO> createARMEmulator() {
        return DarwinEmulatorBuilder.for64Bit().build();
    }

    private void processMount() {
        Module module = emulator.loadLibrary(new File("unidbg-ios/src/test/resources/example_binaries/mount"));

        long start = System.currentTimeMillis();
        int ret = module.callEntry(emulator);
        System.err.println("processMount ret=0x" + Integer.toHexString(ret) + ", offset=" + (System.currentTimeMillis() - start) + "ms");
    }

    public static void main(String[] args) throws Exception {
        MountTest test = new MountTest();
        test.setUp();
        test.processMount();
        test.tearDown();
    }

}
