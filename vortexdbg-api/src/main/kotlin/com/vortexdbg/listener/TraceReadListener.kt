package com.vortexdbg.listener

import com.vortexdbg.Emulator

interface TraceReadListener {

    /**
     * @return 返回<code>true</code>打印内存信息
     */
    fun onRead(emulator: Emulator<*>, address: Long, data: ByteArray, hex: String): Boolean

}
