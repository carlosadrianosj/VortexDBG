package com.vortexdbg.signal;

import com.vortexdbg.thread.BaseTask;

public abstract class AbstractSignalTask extends BaseTask implements com.vortexdbg.signal.SignalTask {

    protected final int signum;

    public AbstractSignalTask(int signum) {
        this.signum = signum;
    }

    @Override
    protected final String getStatus() {
        return "Signal: " + signum;
    }

}
