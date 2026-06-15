package com.vortexdbg.ios.simulator;

import com.vortexdbg.Emulator;
import com.vortexdbg.LibraryResolver;
import com.vortexdbg.Utils;
import com.vortexdbg.spi.LibraryFile;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

class SimpleLibraryFile implements LibraryFile {

    private final LibraryResolver libraryResolver;
    private final File file;
    private final String path;

    SimpleLibraryFile(LibraryResolver libraryResolver, File file, String path) {
        this.libraryResolver = libraryResolver;
        this.file = file;
        this.path = path;
    }

    @Override
    public String getName() {
        return FilenameUtils.getName(path);
    }

    @Override
    public String getMapRegionName() {
        return getPath();
    }

    @Override
    public LibraryFile resolveLibrary(Emulator<?> emulator, String dylibName) {
        return libraryResolver.resolveLibrary(emulator, dylibName);
    }

    @Override
    public ByteBuffer mapBuffer() throws IOException {
        return Utils.mapBuffer(file);
    }

    @Override
    public long getFileSize() {
        return file.length();
    }

    @Override
    public String getPath() {
        return path;
    }

}
