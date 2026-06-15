package com.vortexdbg.signal;

import com.vortexdbg.AbstractEmulator;
import com.vortexdbg.thread.RunnableTask;

public interface SignalTask extends RunnableTask {

    Number callHandler(SignalOps signalOps, AbstractEmulator<?> emulator);

}
