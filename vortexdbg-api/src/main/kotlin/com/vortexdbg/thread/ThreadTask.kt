package com.vortexdbg.thread

import com.vortexdbg.AbstractEmulator

abstract class ThreadTask protected constructor(tid: Int, @JvmField protected val until: Long) : AbstractTask(tid), Task {

    final override fun isMainThread(): Boolean {
        return false
    }

    private var finished = false

    override fun isFinish(): Boolean {
        return finished
    }

    @JvmField
    protected var exitStatus: Int = 0

    open fun setExitStatus(status: Int) {
        this.exitStatus = status
        this.finished = true
    }

    @Throws(PopContextException::class)
    final override fun dispatch(emulator: AbstractEmulator<*>): Number? {
        if (isContextSaved()) {
            return continueRun(emulator, until)
        }
        return runThread(emulator)
    }

    protected abstract fun runThread(emulator: AbstractEmulator<*>): Number?

}
