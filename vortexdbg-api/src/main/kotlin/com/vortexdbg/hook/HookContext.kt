package com.vortexdbg.hook

import com.vortexdbg.arm.context.RegisterContext
import java.util.Stack

abstract class HookContext internal constructor(private val stack: Stack<Any?>) : RegisterContext, InvocationContext {

    override fun push(vararg objs: Any?) {
        for (i in objs.indices.reversed()) {
            stack.push(objs[i])
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> pop(): T {
        return stack.pop() as T
    }
}
