package com.vortexdbg.arm.backend

interface Detachable {

    fun onAttach(unHook: UnHook)

    fun detach()

}
