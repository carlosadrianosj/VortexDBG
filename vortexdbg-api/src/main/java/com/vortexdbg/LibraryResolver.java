package com.vortexdbg;

import com.vortexdbg.spi.LibraryFile;

public interface LibraryResolver {

    LibraryFile resolveLibrary(Emulator<?> emulator, String libraryName);

    void onSetToLoader(Emulator<?> emulator);
}
