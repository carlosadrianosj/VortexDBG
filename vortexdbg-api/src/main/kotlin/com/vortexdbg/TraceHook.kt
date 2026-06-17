package com.vortexdbg

import java.io.PrintStream

interface TraceHook {

    fun setRedirect(redirect: PrintStream?)

    fun stopTrace()

}
