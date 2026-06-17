package com.vortexdbg.hook.hookzz

import com.vortexdbg.Svc
import com.vortexdbg.Symbol
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.hook.IHook
import com.vortexdbg.hook.InlineHook
import com.vortexdbg.hook.ReplaceCallback

interface IHookZz : IHook, InlineHook {

    fun enable_arm_arm64_b_branch()
    fun disable_arm_arm64_b_branch()

    fun switch_to_file_log(path: String)

    fun <T : RegisterContext> wrap(functionAddress: Long, callback: WrapCallback<T>)
    fun <T : RegisterContext> wrap(symbol: Symbol, callback: WrapCallback<T>)

    override fun replace(functionAddress: Long, callback: ReplaceCallback)
    override fun replace(symbol: Symbol, callback: ReplaceCallback)

    override fun replace(functionAddress: Long, replace: Svc)
    override fun replace(symbol: Symbol, replace: Svc)

    override fun replace(functionAddress: Long, callback: ReplaceCallback, enablePostCall: Boolean)
    override fun replace(symbol: Symbol, callback: ReplaceCallback, enablePostCall: Boolean)

    fun <T : RegisterContext> instrument(functionAddress: Long, callback: InstrumentCallback<T>)
    fun <T : RegisterContext> instrument(symbol: Symbol, callback: InstrumentCallback<T>)

}
