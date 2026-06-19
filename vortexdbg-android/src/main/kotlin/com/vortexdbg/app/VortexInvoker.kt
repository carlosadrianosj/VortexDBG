package com.vortexdbg.app

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * LSPosed-style invocation of the app's classes — but OFF-DEVICE, on the host JVM.
 *
 * Instead of injecting into Zygote and calling classes on a physical device, the analyst
 * uses VortexInvoker to load an app class (via [VortexClassLoader]) and invoke a method
 * with arbitrary arguments by reflection, getting the real return value back. This is the
 * core of the A1 architecture's use case.
 */
open class VortexInvoker(private val classLoader: ClassLoader) {

    @Throws(ClassNotFoundException::class)
    open fun load(className: String): Class<*> {
        return Class.forName(className, true, classLoader)
    }

    /** Invokes a static method with explicit parameter types. */
    @Throws(Exception::class)
    open fun invokeStatic(className: String, method: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? {
        val c = load(className)
        val m = findMethod(c, method, paramTypes)
        m.isAccessible = true
        try {
            return m.invoke(null, *args)
        } catch (e: InvocationTargetException) {
            throw unwrap(e)
        }
    }

    /** Invokes an instance method with explicit parameter types. */
    @Throws(Exception::class)
    open fun invoke(target: Any, method: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? {
        val m = findMethod(target.javaClass, method, paramTypes)
        m.isAccessible = true
        try {
            return m.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw unwrap(e)
        }
    }

    companion object {
        /** Looks up the method on the class and up its superclass hierarchy. */
        @Throws(NoSuchMethodException::class)
        private fun findMethod(c: Class<*>, method: String, paramTypes: Array<Class<*>>): Method {
            var k: Class<*>? = c
            while (k != null) {
                try {
                    return k.getDeclaredMethod(method, *paramTypes)
                } catch (ignored: NoSuchMethodException) {
                    // not declared here; keep walking up the hierarchy
                }
                k = k.superclass
            }
            throw NoSuchMethodException(c.name + "." + method)
        }

        private fun unwrap(e: InvocationTargetException): Exception {
            val cause = e.targetException
            if (cause is Exception) {
                return cause
            }
            return e
        }
    }
}
