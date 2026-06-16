package com.vortexdbg.hook;

import com.vortexdbg.Emulator;

public interface InterceptCallback {

    void onIntercept(Emulator<?> emulator);

}
