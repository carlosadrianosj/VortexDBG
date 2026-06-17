package com.vortexdbg.linux.android.dvm.jni

import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.linux.android.dvm.VM

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method

internal class ProxyMethod internal constructor(
    private val visitor: ProxyDvmObjectVisitor?,
    private val method: Member,
    private val args: Array<Any?>
) : ProxyCall {

    @Throws(IllegalAccessException::class, InvocationTargetException::class)
    override fun call(vm: VM, obj: Any?): Any? {
        try {
            patchClassName(obj, args)

            if (visitor != null) {
                visitor.onProxyVisit(method, obj, args)
            }
            if (method is Method) {
                var result = method.invoke(obj, *args)
                if (visitor != null) {
                    result = visitor.postProxyVisit(method, obj, args, result)
                }
                return result
            }
            throw UnsupportedOperationException("method=$method")
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause is BackendException) {
                throw cause
            }
            if (cause is ProxyDvmException) {
                vm.throwException(ProxyDvmObject.createObject(vm, cause))
                return null
            }
            if (cause is ClassNotFoundException) {
                vm.throwException(ProxyDvmObject.createObject(vm, cause))
                return null
            }
            throw e
        }
    }

    private fun patchClassName(obj: Any?, args: Array<Any?>) {
        if (obj is ClassLoader &&
            args.size == 1 &&
            ("loadClass" == method.name || "findClass" == method.name)
        ) {
            val binaryName = args[0] as String
            args[0] = binaryName.replace('/', '.')
        }
    }
}
