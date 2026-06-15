package com.vortexdbg.thread;

import com.vortexdbg.signal.SignalTask;

public abstract class AbstractWaiter implements Waiter {

    @Override
    public void onSignal(SignalTask task) {
    }

}
