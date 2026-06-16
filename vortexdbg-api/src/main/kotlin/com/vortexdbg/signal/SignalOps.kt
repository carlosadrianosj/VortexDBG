package com.vortexdbg.signal

interface SignalOps {

    fun getSigMaskSet(): SigSet
    fun setSigMaskSet(sigMaskSet: SigSet)

    fun getSigPendingSet(): SigSet
    fun setSigPendingSet(sigPendingSet: SigSet)

}
