package com.vortexdbg.signal

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.thread.RunnableTask

interface SignalTask : RunnableTask {

    fun callHandler(signalOps: SignalOps, emulator: AbstractEmulator<*>): Number

}
