package com.vortexdbg.thread

import com.vortexdbg.Emulator
import com.vortexdbg.signal.SignalTask

interface Waiter {

    fun canDispatch(): Boolean

    fun onContinueRun(emulator: Emulator<*>)

    fun onSignal(task: SignalTask)
}
