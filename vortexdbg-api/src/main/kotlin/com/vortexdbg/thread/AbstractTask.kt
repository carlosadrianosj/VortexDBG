package com.vortexdbg.thread

import com.vortexdbg.Emulator
import com.vortexdbg.signal.SigSet
import com.vortexdbg.signal.SignalTask
import java.util.ArrayList
import java.util.Collections

abstract class AbstractTask(@JvmField protected val id: Int) : BaseTask(), Task {

    private var sigMaskSet: SigSet? = null
    private var sigPendingSet: SigSet? = null

    override fun getSigMaskSet(): SigSet? {
        return sigMaskSet
    }

    override fun getSigPendingSet(): SigSet? {
        return sigPendingSet
    }

    override fun setSigMaskSet(sigMaskSet: SigSet) {
        this.sigMaskSet = sigMaskSet
    }

    override fun setSigPendingSet(sigPendingSet: SigSet) {
        this.sigPendingSet = sigPendingSet
    }

    override fun getId(): Int {
        return id
    }

    private val signalTaskList: MutableList<SignalTask> = ArrayList()

    final override fun addSignalTask(task: SignalTask) {
        signalTaskList.add(task)

        val waiter = getWaiter()
        if (waiter != null) {
            waiter.onSignal(task)
        }
    }

    override fun removeSignalTask(task: SignalTask) {
        signalTaskList.remove(task)
    }

    override fun getSignalTaskList(): List<SignalTask> {
        return if (signalTaskList.isEmpty()) Collections.emptyList() else ArrayList(signalTaskList)
    }

    override fun setErrno(emulator: Emulator<*>, errno: Int): Boolean {
        return false
    }

    protected final override fun getStatus(): String {
        return if (isFinish()) {
            "Finished"
        } else if (canDispatch()) {
            "Runnable"
        } else {
            "Paused"
        }
    }

}
