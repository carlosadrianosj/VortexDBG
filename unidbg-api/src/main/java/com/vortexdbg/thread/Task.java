package com.vortexdbg.thread;

import com.vortexdbg.AbstractEmulator;
import com.vortexdbg.Emulator;
import com.vortexdbg.signal.SignalOps;
import com.vortexdbg.signal.SignalTask;

import java.util.List;

public interface Task extends SignalOps, RunnableTask {

    String TASK_KEY = Task.class.getName();

    int getId();

    Number dispatch(AbstractEmulator<?> emulator) throws PopContextException;

    boolean isMainThread();

    boolean isFinish();

    void addSignalTask(SignalTask task);

    List<SignalTask> getSignalTaskList();

    void removeSignalTask(SignalTask task);

    boolean setErrno(Emulator<?> emulator, int errno);

}
