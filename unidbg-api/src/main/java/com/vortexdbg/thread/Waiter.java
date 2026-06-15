package com.vortexdbg.thread;

import com.vortexdbg.Emulator;
import com.vortexdbg.signal.SignalTask;

public interface Waiter {

    boolean canDispatch();

    void onContinueRun(Emulator<?> emulator);

    void onSignal(SignalTask task);
}
