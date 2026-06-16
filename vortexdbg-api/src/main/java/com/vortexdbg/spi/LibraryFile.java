package com.vortexdbg.spi;

import com.vortexdbg.Emulator;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface LibraryFile {

    String getName();

    String getMapRegionName();

    LibraryFile resolveLibrary(Emulator<?> emulator, String soName) throws IOException;

    ByteBuffer mapBuffer() throws IOException;

    String getPath();

    long getFileSize();

}
