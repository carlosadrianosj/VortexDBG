package com.vortexdbg.listener

import com.vortexdbg.Emulator

interface TraceSystemMemoryWriteListener {

    fun onWrite(emulator: Emulator<*>, address: Long, buf: ByteArray)

}
