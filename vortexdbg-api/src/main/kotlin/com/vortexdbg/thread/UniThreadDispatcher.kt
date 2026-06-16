package com.vortexdbg.thread

import com.vortexdbg.AbstractEmulator
import com.vortexdbg.signal.SigSet
import com.vortexdbg.signal.SignalOps
import com.vortexdbg.signal.SignalTask
import com.vortexdbg.signal.UnixSigSet
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * 抢占式调度
 */
open class UniThreadDispatcher(private val emulator: AbstractEmulator<*>) : ThreadDispatcher {

    private val taskList: MutableList<Task> = ArrayList()

    private val threadTaskList: MutableList<ThreadTask> = ArrayList()

    override fun addThread(task: ThreadTask) {
        threadTaskList.add(task)
    }

    override fun getTaskList(): List<Task> {
        return taskList
    }

    override fun sendSignal(tid: Int, sig: Int, signalTask: SignalTask?): Boolean {
        val list: MutableList<Task> = ArrayList()
        list.addAll(taskList)
        list.addAll(threadTaskList)
        var ret = false
        for (task in list) {
            var signalOps: SignalOps? = null
            if (tid == 0 && task.isMainThread()) {
                signalOps = this
            }
            if (tid == task.getId()) {
                signalOps = task
            }
            if (signalOps == null) {
                continue
            }
            val sigSet = signalOps.getSigMaskSet()
            var sigPendingSet = signalOps.getSigPendingSet()
            if (sigPendingSet == null) {
                sigPendingSet = UnixSigSet(0)
                signalOps.setSigPendingSet(sigPendingSet)
            }
            if (sigSet != null && sigSet.containsSigNumber(sig)) {
                sigPendingSet.addSigNumber(sig)
                return false
            }
            if (signalTask != null) {
                task.addSignalTask(signalTask)
                if (log.isTraceEnabled) {
                    emulator.attach().debug("Signal dispatched: sig=$sig")
                }
            } else {
                sigPendingSet.addSigNumber(sig)
            }
            ret = true
            break
        }
        return ret
    }

    private var runningTask: RunnableTask? = null

    override fun getRunningTask(): RunnableTask? {
        return runningTask
    }

    override fun runMainForResult(main: MainTask): Number? {
        taskList.add(0, main)

        log.debug("runMainForResult main={}", main)

        val ret = run(0, null)
        val iterator = taskList.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (task.isFinish()) {
                log.debug("Finish task={}", task)
                task.destroy(emulator)
                iterator.remove()
                for (signalTask in task.getSignalTaskList()) {
                    signalTask.destroy(emulator)
                    task.removeSignalTask(signalTask)
                }
            }
        }
        return ret
    }

    override fun runThreads(timeout: Long, unit: TimeUnit) {
        if (timeout <= 0 || unit == null) {
            throw IllegalArgumentException("Invalid timeout.")
        }
        run(timeout, unit)
    }

    private fun run(timeout: Long, unit: TimeUnit?): Number? {
        try {
            val start = System.currentTimeMillis()
            while (true) {
                if (taskList.isEmpty()) {
                    throw IllegalStateException()
                }
                val iterator = taskList.iterator()
                while (iterator.hasNext()) {
                    val task = iterator.next()
                    if (task.isFinish()) {
                        continue
                    }
                    if (task.canDispatch()) {
                        log.debug("Start dispatch task={}", task)
                        emulator.set(Task.TASK_KEY, task)

                        if (task.isContextSaved()) {
                            task.restoreContext(emulator)
                            for (signalTask in task.getSignalTaskList()) {
                                if (signalTask.canDispatch()) {
                                    log.debug("Start run signalTask={}", signalTask)
                                    val ops: SignalOps = if (task.isMainThread()) this else task
                                    try {
                                        this.runningTask = signalTask
                                        val ret = signalTask.callHandler(ops, emulator)
                                        log.debug("End run signalTask={}, ret={}", signalTask, ret)
                                        if (ret != null) {
                                            signalTask.setResult(emulator, ret)
                                            signalTask.destroy(emulator)
                                            task.removeSignalTask(signalTask)
                                        } else {
                                            signalTask.saveContext(emulator)
                                        }
                                    } catch (e: PopContextException) {
                                        this.runningTask!!.popContext(emulator)
                                    }
                                } else {
                                    log.debug("Skip call handler signalTask={}", signalTask)
                                }
                            }
                        }

                        try {
                            this.runningTask = task
                            val ret = task.dispatch(emulator)
                            log.debug("End dispatch task={}, ret={}", task, ret)
                            if (ret != null) {
                                task.setResult(emulator, ret)
                                task.destroy(emulator)
                                iterator.remove()
                                if (task.isMainThread()) {
                                    return ret
                                }
                            } else {
                                task.saveContext(emulator)
                            }
                        } catch (e: PopContextException) {
                            this.runningTask!!.popContext(emulator)
                        }
                    } else {
                        if (log.isTraceEnabled && task.isContextSaved()) {
                            task.restoreContext(emulator)
                            log.trace("Skip dispatch task={}", task)
                            emulator.getUnwinder().unwind()
                        } else {
                            log.debug("Skip dispatch task={}", task)
                        }
                    }
                }

                Collections.reverse(threadTaskList)
                val threadIterator = threadTaskList.iterator()
                while (threadIterator.hasNext()) {
                    taskList.add(0, threadIterator.next())
                    threadIterator.remove()
                }

                if (timeout > 0 && unit != null &&
                    System.currentTimeMillis() - start >= unit.toMillis(timeout)
                ) {
                    return null
                }
                if (taskList.isEmpty()) {
                    return null
                }

                if (log.isDebugEnabled) {
                    try {
                        TimeUnit.SECONDS.sleep(1)
                    } catch (ignored: InterruptedException) {
                    }
                }
            }
        } finally {
            this.runningTask = null
            emulator.set(Task.TASK_KEY, null)
        }
    }

    override fun getTaskCount(): Int {
        return taskList.size + threadTaskList.size
    }

    private var mainThreadSigMaskSet: SigSet? = null
    private var mainThreadSigPendingSet: SigSet? = null

    override fun getSigMaskSet(): SigSet? {
        return mainThreadSigMaskSet
    }

    override fun setSigMaskSet(sigMaskSet: SigSet) {
        this.mainThreadSigMaskSet = sigMaskSet
    }

    override fun getSigPendingSet(): SigSet? {
        return mainThreadSigPendingSet
    }

    override fun setSigPendingSet(sigPendingSet: SigSet) {
        this.mainThreadSigPendingSet = sigPendingSet
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(UniThreadDispatcher::class.java)
    }
}
