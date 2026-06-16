package com.vortexdbg.hook.whale

import com.vortexdbg.Symbol
import com.vortexdbg.hook.IHook
import com.vortexdbg.hook.InlineHook
import com.vortexdbg.hook.ReplaceCallback

interface IWhale : IHook, InlineHook {

    fun inlineHookFunction(address: Long, callback: ReplaceCallback)
    fun inlineHookFunction(symbol: Symbol, callback: ReplaceCallback)

    fun inlineHookFunction(address: Long, callback: ReplaceCallback, enablePostCall: Boolean)
    @Suppress("unused")
    fun inlineHookFunction(symbol: Symbol, callback: ReplaceCallback, enablePostCall: Boolean)

    /**
     * 当前对android无效，参考：https://github.com/asLody/whale/blob/master/whale/src/whale.cc，只支持苹果
     */
    @Suppress("unused")
    fun importHookFunction(symbol: String, callback: ReplaceCallback)
    fun importHookFunction(symbol: String, callback: ReplaceCallback, enablePostCall: Boolean)

}
