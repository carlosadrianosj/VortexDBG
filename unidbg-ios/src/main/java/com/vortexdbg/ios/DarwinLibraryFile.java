package com.vortexdbg.ios;

import com.vortexdbg.spi.LibraryFile;

public interface DarwinLibraryFile extends LibraryFile {

    String resolveBootstrapPath();

}
