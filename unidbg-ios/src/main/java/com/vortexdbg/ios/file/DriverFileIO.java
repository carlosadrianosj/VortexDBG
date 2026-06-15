package com.vortexdbg.ios.file;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.backend.Backend;
import com.vortexdbg.file.NewFileIO;
import com.vortexdbg.file.ios.BaseDarwinFileIO;
import com.vortexdbg.file.ios.DarwinFileIO;
import com.vortexdbg.file.ios.StatStructure;
import com.vortexdbg.ios.struct.attr.AttrList;
import com.vortexdbg.ios.struct.kernel.StatFS;
import com.sun.jna.Pointer;

public class DriverFileIO extends BaseDarwinFileIO implements NewFileIO, DarwinFileIO {

    public static DriverFileIO create(Emulator<?> emulator, int oflags, String pathname) {
        if ("/dev/urandom".equals(pathname) || "/dev/random".equals(pathname) || "/dev/srandom".equals(pathname)) {
            return new RandomFileIO(emulator, pathname);
        }
        if ("/dev/null".equals(pathname)) {
            return new DriverFileIO(emulator, oflags, pathname);
        }
        return null;
    }

    private final String path;

    @SuppressWarnings("unused")
    DriverFileIO(Emulator<?> emulator, int oflags, String path) {
        super(oflags);
        this.path = path;
    }

    @Override
    public void close() {
    }

    @Override
    public int write(byte[] data) {
        throw new AbstractMethodError();
    }

    @Override
    public int read(Backend backend, Pointer buffer, int count) {
        throw new AbstractMethodError();
    }

    @Override
    public int ioctl(Emulator<?> emulator, long request, long argp) {
        return super.ioctl(emulator, request, argp);
    }

    @Override
    public int fstat(Emulator<?> emulator, StatStructure stat) {
        return 0;
    }

    @Override
    public int fstatfs(StatFS statFS) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getattrlist(AttrList attrList, Pointer attrBuf, int attrBufSize) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getdirentries64(Pointer buf, int bufSize) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        return path;
    }
}
