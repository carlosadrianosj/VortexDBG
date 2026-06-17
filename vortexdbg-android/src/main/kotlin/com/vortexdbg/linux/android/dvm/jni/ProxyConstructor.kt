package com.vortexdbg.linux.android.dvm.jni

import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.linux.android.dvm.VM

import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException

internal class ProxyConstructor internal constructor(
    private val visitor: ProxyDvmObjectVisitor?,
    private val constructor: Constructor<*>,
    private val args: Array<Any?>
) : ProxyCall {

    @Throws(IllegalAccessException::class, InvocationTargetException::class, InstantiationException::class)
    override fun call(vm: VM, obj: Any?): Any? {
        try {
            var inst = constructor.newInstance(*args)
            if (visitor != null) {
                visitor.onProxyVisit(constructor, inst, args)
                inst = visitor.postProxyVisit(constructor, inst, args, inst)
            }
            return inst
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause is BackendException) {
                throw cause
            }
            if (cause is ProxyDvmException) {
                vm.throwException(ProxyDvmObject.createObject(vm, cause))
                return null
            }
            throw e
        }
    }

}
