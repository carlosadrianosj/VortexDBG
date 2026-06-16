package com.vortexdbg.linux.android;

import com.vortexdbg.Emulator;
import com.vortexdbg.Utils;
import com.vortexdbg.spi.LibraryFile;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ElfLibraryFile implements LibraryFile {

    private final File elfFile;
    private final boolean is64Bit;

    public ElfLibraryFile(File elfFile, boolean is64Bit) {
        this.elfFile = elfFile;
        this.is64Bit = is64Bit;
    }

    @Override
    public long getFileSize() {
        return elfFile.length();
    }

    @Override
    public String getName() {
        return elfFile.getName();
    }

    @Override
    public String getMapRegionName() {
        return getPath();
    }

    @Override
    public LibraryFile resolveLibrary(Emulator<?> emulator, String soName) {
        File file = new File(elfFile.getParentFile(), soName);
        return file.canRead() ? new ElfLibraryFile(file, is64Bit) : null;
    }

    @Override
    public ByteBuffer mapBuffer() throws IOException {
        return Utils.mapBuffer(elfFile);
    }

    @Override
    public String getPath() {
        String name = getName();
        if (name.endsWith(".so")) {
            return "/system/" + (is64Bit ? "lib64/" : "lib/") + name;
        } else {
            return "/system/bin/" + name;
        }
    }

}
