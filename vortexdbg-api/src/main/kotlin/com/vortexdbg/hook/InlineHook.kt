package com.vortexdbg.hook

import com.vortexdbg.Svc
import com.vortexdbg.Symbol

interface InlineHook {

    fun replace(functionAddress: Long, callback: ReplaceCallback)
    fun replace(symbol: Symbol, callback: ReplaceCallback)

    fun replace(functionAddress: Long, replace: Svc)
    fun replace(symbol: Symbol, replace: Svc)

    fun replace(functionAddress: Long, callback: ReplaceCallback, enablePostCall: Boolean)
    fun replace(symbol: Symbol, callback: ReplaceCallback, enablePostCall: Boolean)

}
