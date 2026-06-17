package com.vortexdbg.app

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Invocação estilo LSPosed das classes do app — porém OFF-DEVICE, na JVM host.
 *
 * Em vez de injetar no Zygote e chamar classes num device físico, o analista usa o
 * VortexInvoker para carregar a classe do app (via [VortexClassLoader]) e
 * invocar um método com argumentos arbitrários por reflexão, recebendo o valor de
 * retorno real. É o núcleo do caso de uso da arquitetura A1.
 */
open class VortexInvoker(private val classLoader: ClassLoader) {

    @Throws(ClassNotFoundException::class)
    open fun load(className: String): Class<*> {
        return Class.forName(className, true, classLoader)
    }

    /** Invoca um método estático com tipos de parâmetro explícitos. */
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

    /** Invoca um método de instância com tipos de parâmetro explícitos. */
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
        /** Procura o método na classe e na hierarquia de superclasses. */
        @Throws(NoSuchMethodException::class)
        private fun findMethod(c: Class<*>, method: String, paramTypes: Array<Class<*>>): Method {
            var k: Class<*>? = c
            while (k != null) {
                try {
                    return k.getDeclaredMethod(method, *paramTypes)
                } catch (ignored: NoSuchMethodException) {
                    // sobe na hierarquia
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
