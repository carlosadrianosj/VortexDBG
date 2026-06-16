package com.vortexdbg.signal

import com.vortexdbg.thread.BaseTask

abstract class AbstractSignalTask(@JvmField protected val signum: Int) : BaseTask(), com.vortexdbg.signal.SignalTask {

    protected final override fun getStatus(): String {
        return "Signal: $signum"
    }

}
