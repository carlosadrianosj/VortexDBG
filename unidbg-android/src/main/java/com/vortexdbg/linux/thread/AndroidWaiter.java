package com.vortexdbg.linux.thread;

import com.vortexdbg.Emulator;
import com.vortexdbg.thread.AbstractWaiter;
import com.vortexdbg.thread.Waiter;

public abstract class AndroidWaiter extends AbstractWaiter implements Waiter {

    @Override
    public void onContinueRun(Emulator<?> emulator) {
    }
}
