package com.vortexdbg.thread

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.signal.SignalOps
import com.vortexdbg.signal.SignalTask

interface Task : SignalOps, RunnableTask {

    fun getId(): Int

    @Throws(PopContextException::class)
    fun dispatch(emulator: AbstractEmulator<*>): Number?

    fun isMainThread(): Boolean

    fun isFinish(): Boolean

    fun addSignalTask(task: SignalTask)

    fun getSignalTaskList(): List<SignalTask>

    fun removeSignalTask(task: SignalTask)

    fun setErrno(emulator: Emulator<*>, errno: Int): Boolean

    companion object {
        @JvmField
        val TASK_KEY: String = Task::class.java.name
    }

}
