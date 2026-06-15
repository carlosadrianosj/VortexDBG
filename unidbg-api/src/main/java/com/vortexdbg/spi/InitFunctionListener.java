package com.vortexdbg.spi;

import com.vortexdbg.Module;

public interface InitFunctionListener {

    void onPreCallInitFunction(Module module, long initFunction, int index);

    void onPostCallInitFunction(Module module, long initFunction, int index);

}
