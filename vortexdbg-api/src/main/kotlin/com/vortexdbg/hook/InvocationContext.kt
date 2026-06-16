package com.vortexdbg.hook

interface InvocationContext {

    fun push(vararg objs: Any?)

    fun <T> pop(): T

}
