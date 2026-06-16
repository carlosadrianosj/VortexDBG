package com.vortexdbg.listener

import com.vortexdbg.Emulator

interface TraceWriteListener {

    /**
     * @return 返回<code>true</code>打印内存信息
     */
    fun onWrite(emulator: Emulator<*>, address: Long, size: Int, value: Long): Boolean

}
