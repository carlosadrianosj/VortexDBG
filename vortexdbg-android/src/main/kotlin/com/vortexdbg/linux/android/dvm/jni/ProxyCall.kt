package com.vortexdbg.linux.android.dvm.jni

import com.vortexdbg.linux.android.dvm.VM

import java.lang.reflect.InvocationTargetException

internal interface ProxyCall {

    @Throws(IllegalAccessException::class, InvocationTargetException::class, InstantiationException::class)
    fun call(vm: VM, obj: Any?): Any?

}
