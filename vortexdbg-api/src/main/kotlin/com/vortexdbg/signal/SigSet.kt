package com.vortexdbg.signal

interface SigSet : Iterable<Int> {

    fun getMask(): Long

    fun setMask(mask: Long)

    fun blockSigSet(mask: Long)

    fun unblockSigSet(mask: Long)

    fun containsSigNumber(signum: Int): Boolean

    fun removeSigNumber(signum: Int)

    fun addSigNumber(signum: Int)

}
