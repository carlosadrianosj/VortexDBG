package com.vortexdbg.thread

import com.vortexdbg.signal.SignalTask

abstract class AbstractWaiter : Waiter {

    override fun onSignal(task: SignalTask) {
    }

}
