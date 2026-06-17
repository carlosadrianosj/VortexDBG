package com.vortexdbg.hook.hookzz

import com.vortexdbg.arm.context.AbstractRegisterContext
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.hook.InvocationContext
import com.vortexdbg.pointer.VortexdbgPointer

import java.util.Stack

abstract class HookZzRegisterContext internal constructor(private val stack: Stack<Any?>) : AbstractRegisterContext(), RegisterContext, InvocationContext {

    override fun push(vararg objs: Any?) {
        for (i in objs.indices.reversed()) {
            stack.push(objs[i])
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> pop(): T {
        return stack.pop() as T
    }

    override fun getPCPointer(): VortexdbgPointer {
        throw UnsupportedOperationException()
    }

    override fun getIntByReg(regId: Int): Int {
        throw UnsupportedOperationException()
    }

    override fun getLongByReg(regId: Int): Long {
        throw UnsupportedOperationException()
    }
}
