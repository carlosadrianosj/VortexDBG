package com.vortexdbg.spi;

import com.vortexdbg.Emulator;
import com.vortexdbg.arm.backend.InterruptHook;
import com.vortexdbg.debugger.Breaker;
import com.vortexdbg.file.FileIO;
import com.vortexdbg.file.IOResolver;
import com.vortexdbg.file.NewFileIO;
import com.vortexdbg.serialize.Serializable;
import com.vortexdbg.thread.MainTask;
import com.vortexdbg.unix.FileListener;

/**
 * syscall handler
 * Created by zhkl0228 on 2017/5/9.
 */

public interface SyscallHandler<T extends NewFileIO> extends InterruptHook, Serializable {

    int DARWIN_SWI_SYSCALL = 0x80;

    /**
     * 后面添加的优先级高
     */
    void addIOResolver(IOResolver<T> resolver);

    int open(Emulator<T> emulator, String pathname, int oflags);

    void setVerbose(boolean verbose);
    boolean isVerbose();
    void setFileListener(FileListener fileListener);

    void setBreaker(Breaker breaker);

    void setEnableThreadDispatcher(boolean threadDispatcherEnabled);

    MainTask createSignalHandlerTask(Emulator<?> emulator, int sig);

    FileIO getFileIO(int fd);

    void closeFileIO(int fd);

    int addFileIO(T io);

    void destroy();

}
