package com.vortexdbg.spi;

import com.vortexdbg.Emulator;

public interface InitFunctionFilter {

    boolean accept(Emulator<?> emulator, long address);

}
