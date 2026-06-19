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
     * Hook an imported (PLT/GOT) symbol. Apple/Mach-O only; this has no effect
     * on Android, mirroring the upstream whale implementation
     * (see whale/src/whale.cc in github.com/asLody/whale).
     */
    @Suppress("unused")
    fun importHookFunction(symbol: String, callback: ReplaceCallback)
    fun importHookFunction(symbol: String, callback: ReplaceCallback, enablePostCall: Boolean)

}
