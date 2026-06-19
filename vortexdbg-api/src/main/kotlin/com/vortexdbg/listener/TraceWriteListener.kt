package com.vortexdbg.listener

import com.vortexdbg.Emulator

interface TraceWriteListener {

    /**
     * @return `true` to let the tracer print the memory access information
     */
    fun onWrite(emulator: Emulator<*>, address: Long, size: Int, value: Long): Boolean

}
