package com.vortexdbg.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * E (Vortex-DBG / A1) — classloader que carrega as classes-alvo do app (child-first) e
 * instrumenta seus métodos {@code native} via {@link VortexNativeInstrumentor}, roteando-os
 * ao UniDBG. As demais classes delegam ao parent.
 */
open class VortexInstrumentingClassLoader(parent: ClassLoader?, private val targetPrefix: String) :
    ClassLoader(parent) {

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith(targetPrefix)) {
            synchronized(getClassLoadingLock(name)) {
                var c: Class<*>? = findLoadedClass(name)
                if (c == null) {
                    c = defineInstrumented(name)
                }
                if (resolve) {
                    resolveClass(c)
                }
                return c
            }
        }
        return super.loadClass(name, resolve)
    }

    @Throws(ClassNotFoundException::class)
    private fun defineInstrumented(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        try {
            val `in`: InputStream? = parent.getResourceAsStream(path)
            `in`.use {
                if (`in` == null) {
                    throw ClassNotFoundException(name)
                }
                val original = readAll(`in`)
                val instrumented = VortexNativeInstrumentor.instrument(original)
                return defineClass(name, instrumented, 0, instrumented.size)
            }
        } catch (e: IOException) {
            throw ClassNotFoundException(name, e)
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun readAll(`in`: InputStream): ByteArray {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n: Int
            while (`in`.read(buf).also { n = it } != -1) {
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        }
    }
}
