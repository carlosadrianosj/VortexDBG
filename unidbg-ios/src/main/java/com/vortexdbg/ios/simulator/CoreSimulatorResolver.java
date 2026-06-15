package com.vortexdbg.ios.simulator;

import com.vortexdbg.Emulator;
import com.vortexdbg.LibraryResolver;
import com.vortexdbg.file.FileResult;
import com.vortexdbg.file.IOResolver;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.spi.LibraryFile;

import java.io.File;

public class CoreSimulatorResolver implements LibraryResolver, IOResolver<DarwinFileIO> {

    private final File runtimeRoot;

    public CoreSimulatorResolver(File runtimeRoot) {
        this.runtimeRoot = runtimeRoot;

        if (!runtimeRoot.exists() || !runtimeRoot.isDirectory()) {
            throw new IllegalArgumentException("runtimeRoot=" + runtimeRoot);
        }
    }

    @Override
    public LibraryFile resolveLibrary(Emulator<?> emulator, String libraryName) {
        if ("/usr/lib/system/libsystem_platform.dylib".equals(libraryName) ||
                "/usr/lib/system/libsystem_pthread.dylib".equals(libraryName) ||
                "/usr/lib/system/libsystem_kernel.dylib".equals(libraryName)) {
            return new SimpleLibraryFile(this, new File(libraryName), libraryName);
        }

        File lib = new File(runtimeRoot, libraryName);
        if (lib.exists()) {
            return new SimpleLibraryFile(this, lib, libraryName);
        }
        return null;
    }

    @Override
    public FileResult<DarwinFileIO> resolve(Emulator<DarwinFileIO> emulator, String pathname, int oflags) {
        return null;
    }

    @Override
    public void onSetToLoader(Emulator<?> emulator) {
    }

}
