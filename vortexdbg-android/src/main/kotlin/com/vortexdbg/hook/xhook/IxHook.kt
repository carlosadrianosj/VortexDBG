package com.vortexdbg.hook.xhook

import com.vortexdbg.hook.IHook
import com.vortexdbg.hook.ReplaceCallback

/**
 * Only support android
 */
interface IxHook : IHook {

    fun register(pathname_regex_str: String, symbol: String, callback: ReplaceCallback)
    fun register(pathname_regex_str: String, symbol: String, callback: ReplaceCallback, enablePostCall: Boolean)

    fun refresh()

    companion object {
        const val RET_SUCCESS = 0
    }

}
