package com.vortexdbg.mcp

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Merges keyboard stdin and piped MCP command input.
 * Reads from whichever stream has available data, prioritizing piped commands.
 */
open class MergedInputStream(
    private val keyboard: InputStream,
    private val pipe: InputStream
) : InputStream() {

    @Volatile
    private var pipeClosed = false

    private fun pipeAvailable(): Int {
        if (pipeClosed) {
            return 0
        }
        return try {
            pipe.available()
        } catch (e: IOException) {
            pipeClosed = true
            0
        }
    }

    private fun keyboardAvailable(): Int {
        return try {
            keyboard.available()
        } catch (e: IOException) {
            0
        }
    }

    @Throws(IOException::class)
    override fun read(): Int {
        while (true) {
            if (pipeAvailable() > 0) {
                return pipe.read()
            }
            if (keyboardAvailable() > 0) {
                return keyboard.read()
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
        }
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            val pipeAvail = pipeAvailable()
            if (pipeAvail > 0) {
                return pipe.read(b, off, Math.min(len, pipeAvail))
            }
            val kbAvail = keyboardAvailable()
            if (kbAvail > 0) {
                return keyboard.read(b, off, Math.min(len, kbAvail))
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
        }
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return pipeAvailable() + keyboardAvailable()
    }

    @Throws(IOException::class)
    override fun close() {
        pipeClosed = true
        pipe.close()
        keyboard.close()
    }
}
