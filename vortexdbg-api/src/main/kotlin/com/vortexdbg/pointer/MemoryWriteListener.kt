package com.vortexdbg.pointer

interface MemoryWriteListener {

    fun onSystemWrite(addr: Long, data: ByteArray)

}
