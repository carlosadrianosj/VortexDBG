package com.vortexdbg.file

interface StdoutCallback {

    /**
     * @return `true`表示打印
     */
    fun notifyOut(data: ByteArray, err: Boolean): Boolean

}
