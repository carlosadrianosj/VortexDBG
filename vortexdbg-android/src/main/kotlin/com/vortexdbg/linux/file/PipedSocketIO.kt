package com.vortexdbg.linux.file

import com.vortexdbg.Emulator
import com.vortexdbg.file.FileIO
import com.sun.jna.Pointer

import java.io.BufferedInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

open class PipedSocketIO(emulator: Emulator<*>) : TcpSocket(emulator), FileIO {

    private val pipedInputStream = PipedInputStream()

    init {
        this.inputStream = BufferedInputStream(pipedInputStream)
        this.outputStream = PipedOutputStream()
    }

    fun connectPeer(io: PipedSocketIO) {
        try {
            (this.outputStream as PipedOutputStream).connect(io.pipedInputStream)
            (io.outputStream as PipedOutputStream).connect(this.pipedInputStream)
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun sendto(data: ByteArray, flags: Int, dest_addr: Pointer, addrlen: Int): Int {
        var newFlags = flags and MSG_NOSIGNAL.inv()
        val MSG_EOR = 0x80
        if (newFlags == MSG_EOR && dest_addr == null && addrlen == 0) {
            return write(data)
        }

        return super.sendto(data, newFlags, dest_addr, addrlen)
    }

}
