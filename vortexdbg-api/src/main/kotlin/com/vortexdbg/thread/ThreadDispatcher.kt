package com.vortexdbg.thread

import com.vortexdbg.signal.SignalOps
import com.vortexdbg.signal.SignalTask
import java.util.concurrent.TimeUnit

interface ThreadDispatcher : SignalOps {

    fun addThread(task: ThreadTask)

    fun getTaskList(): List<Task>

    fun runMainForResult(main: MainTask): Number?

    fun runThreads(timeout: Long, unit: TimeUnit)

    fun getTaskCount(): Int

    fun sendSignal(tid: Int, sig: Int, signalTask: SignalTask?): Boolean

    fun getRunningTask(): RunnableTask?

}
