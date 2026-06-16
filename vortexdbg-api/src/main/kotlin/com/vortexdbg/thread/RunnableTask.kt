package com.vortexdbg.thread

import com.vortexdbg.Emulator
import com.vortexdbg.arm.FunctionCall

interface RunnableTask {

    fun canDispatch(): Boolean

    fun saveContext(emulator: Emulator<*>)

    fun isContextSaved(): Boolean

    fun restoreContext(emulator: Emulator<*>)

    fun destroy(emulator: Emulator<*>)

    fun setWaiter(emulator: Emulator<*>, waiter: Waiter?)

    fun getWaiter(): Waiter?

    fun setResult(emulator: Emulator<*>, ret: Number?)

    fun setDestroyListener(listener: DestroyListener?)

    fun popContext(emulator: Emulator<*>)

    fun pushFunction(emulator: Emulator<*>, call: FunctionCall)
    fun popFunction(emulator: Emulator<*>, address: Long): FunctionCall?

}
