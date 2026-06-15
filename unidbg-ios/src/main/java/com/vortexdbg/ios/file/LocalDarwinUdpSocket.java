package com.vortexdbg.ios.file;

import com.vortexdbg.Emulator;
import com.vortexdbg.ios.struct.attr.AttrList;
import com.vortexdbg.unix.UnixEmulator;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalDarwinUdpSocket extends LocalUdpSocket {

    private static final Logger log = LoggerFactory.getLogger(LocalDarwinUdpSocket.class);

    public LocalDarwinUdpSocket(Emulator<?> emulator) {
        super(emulator);
    }

    @Override
    public int connect(Pointer addr, int addrlen) {
        String path = addr.getString(2);
        log.debug("connect path={}", path);

        return connect(path);
    }

    @Override
    protected int connect(String path) {
        emulator.getMemory().setErrno(UnixEmulator.EPERM);
        return -1;
    }

    @Override
    public int getattrlist(AttrList attrList, Pointer attrBuf, int attrBufSize) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getdirentries64(Pointer buf, int bufSize) {
        throw new UnsupportedOperationException();
    }
}
