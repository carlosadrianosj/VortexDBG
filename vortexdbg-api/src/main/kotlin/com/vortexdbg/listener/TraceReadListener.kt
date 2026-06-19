package com.vortexdbg.listener

import com.vortexdbg.Emulator

interface TraceReadListener {

    /**
     * @return `true` to let the tracer print the memory access information
     */
    fun onRead(emulator: Emulator<*>, address: Long, data: ByteArray, hex: String): Boolean

}
