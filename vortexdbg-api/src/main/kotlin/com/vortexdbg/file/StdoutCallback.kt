package com.vortexdbg.file

interface StdoutCallback {

    /**
     * @return `true` to let the emulator also print the data to the host stdout/stderr
     */
    fun notifyOut(data: ByteArray, err: Boolean): Boolean

}
