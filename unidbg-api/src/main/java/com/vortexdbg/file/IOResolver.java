package com.vortexdbg.file;

import com.vortexdbg.Emulator;

public interface IOResolver<T extends NewFileIO> {

    FileResult<T> resolve(Emulator<T> emulator, String pathname, int oflags);

}
