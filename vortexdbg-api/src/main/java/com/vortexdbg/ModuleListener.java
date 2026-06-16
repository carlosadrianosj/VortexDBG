package com.vortexdbg;

public interface ModuleListener {

    void onLoaded(Emulator<?> emulator, Module module);

}
