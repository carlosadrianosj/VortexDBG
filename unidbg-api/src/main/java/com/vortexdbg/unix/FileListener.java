package com.vortexdbg.unix;

import com.vortexdbg.Emulator;
import com.vortexdbg.file.FileIO;

public interface FileListener {

    void onOpenSuccess(Emulator<?> emulator, String pathname, FileIO io);

    void onRead(Emulator<?> emulator, String pathname, byte[] bytes);
    void onWrite(Emulator<?> emulator, String pathname, byte[] bytes);

    void onClose(Emulator<?> emulator, FileIO io);

}
