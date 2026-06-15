package com.vortexdbg.arm.backend;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.backend.dynarmic.Dynarmic;
import com.vortexdbg.arm.backend.dynarmic.DynarmicBackend32;
import com.vortexdbg.arm.backend.dynarmic.DynarmicBackend64;

import java.io.IOException;

public class DynarmicFactory extends BackendFactory {

    static {
        try {
            org.scijava.nativelib.NativeLoader.loadLibrary("dynarmic");
        } catch (IOException ignored) {
        }
    }

    public DynarmicFactory(boolean fallbackUnicorn) {
        super(fallbackUnicorn);
    }

    @Override
    protected Backend newBackendInternal(Emulator<?> emulator, boolean is64Bit) {
        Dynarmic dynarmic = new Dynarmic(is64Bit);
        return is64Bit ? new DynarmicBackend64(emulator, dynarmic) : new DynarmicBackend32(emulator, dynarmic);
    }

}
