package com.vortexdbg.listener;

import com.vortexdbg.Emulator;

public interface TraceWriteListener {

    /**
     * @return 返回<code>true</code>打印内存信息
     */
    boolean onWrite(Emulator<?> emulator, long address, int size, long value);

}
