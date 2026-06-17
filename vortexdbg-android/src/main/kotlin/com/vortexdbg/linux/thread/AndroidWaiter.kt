package com.vortexdbg.linux.thread

import com.vortexdbg.Emulator
import com.vortexdbg.thread.AbstractWaiter
import com.vortexdbg.thread.Waiter

abstract class AndroidWaiter : AbstractWaiter(), Waiter {

    override fun onContinueRun(emulator: Emulator<*>) {
    }
}
