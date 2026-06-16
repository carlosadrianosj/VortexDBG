package com.vortexdbg.thread

import com.vortexdbg.AbstractEmulator

abstract class MainTask protected constructor(pid: Int, @JvmField protected val until: Long) : AbstractTask(pid), Task {

    @Throws(PopContextException::class)
    override fun dispatch(emulator: AbstractEmulator<*>): Number? {
        if (isContextSaved()) {
            return continueRun(emulator, until)
        }
        return run(emulator)
    }

    protected abstract fun run(emulator: AbstractEmulator<*>): Number?

    abstract fun getAddress(): Long

    override fun isMainThread(): Boolean {
        return true
    }

    override fun isFinish(): Boolean {
        return false
    }

}
