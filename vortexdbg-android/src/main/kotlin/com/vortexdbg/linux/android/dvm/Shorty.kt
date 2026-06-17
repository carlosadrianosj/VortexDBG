package com.vortexdbg.linux.android.dvm

import java.lang.reflect.Array

open class Shorty internal constructor(private val arrayDimensions: Int, private val type: Char) {

    private var binaryName: String? = null

    fun setBinaryName(binaryName: String?) {
        this.binaryName = binaryName
    }

    fun getType(): Char {
        return if (arrayDimensions > 0) 'L' else type
    }

    fun decodeType(classLoader: ClassLoader?): Class<*>? {
        var loader = classLoader
        if (loader == null) {
            loader = Shorty::class.java.classLoader
        }

        var clazz: Class<*>? = getPrimitiveType(getType())
        if (clazz != null) {
            return clazz
        }
        var dimensions = this.arrayDimensions
        if (dimensions > 0) {
            try {
                clazz = if (binaryName == null) getPrimitiveType(type) else loader!!.loadClass(binaryName!!.replace('/', '.'))
                if (clazz == null) {
                    throw IllegalStateException("type=$type")
                }
                while (dimensions-- > 0) {
                    clazz = Array.newInstance(clazz, 1).javaClass
                }
                return clazz
            } catch (ignored: ClassNotFoundException) {
            }
            return null
        } else {
            if (binaryName == null) {
                throw IllegalStateException("binaryName is null")
            }
            try {
                clazz = loader!!.loadClass(binaryName!!.replace('/', '.'))
            } catch (ignored: ClassNotFoundException) {
            }
            return clazz
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for (i in 0 until arrayDimensions) {
            sb.append('[')
        }
        sb.append(type)
        if (binaryName != null) {
            sb.append(binaryName).append(';')
        }
        return sb.toString()
    }

    companion object {
        @JvmStatic
        private fun getPrimitiveType(c: Char): Class<*>? {
            return when (c) {
                'B' -> java.lang.Byte.TYPE
                'C' -> Character.TYPE
                'I' -> Integer.TYPE
                'S' -> java.lang.Short.TYPE
                'Z' -> java.lang.Boolean.TYPE
                'F' -> java.lang.Float.TYPE
                'D' -> java.lang.Double.TYPE
                'J' -> java.lang.Long.TYPE
                else -> null
            }
        }
    }
}
