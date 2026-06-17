package com.vortexdbg.linux.android.dvm.jni

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Type

class ProxyReflectedConstructor(private val constructor: Constructor<*>) {

    fun getDeclaringClass(): Class<*> {
        return constructor.declaringClass
    }

    fun getName(): String {
        return constructor.name
    }

    fun getModifiers(): Int {
        return constructor.modifiers
    }

    fun getParameterTypes(): Array<Class<*>> {
        return constructor.parameterTypes
    }

    fun getGenericParameterTypes(): Array<Type> {
        return constructor.genericParameterTypes
    }

    fun getExceptionTypes(): Array<Class<*>> {
        return constructor.exceptionTypes
    }

    fun getGenericExceptionTypes(): Array<Type> {
        return constructor.genericExceptionTypes
    }

    fun toGenericString(): String {
        return constructor.toGenericString()
    }

    @Throws(InstantiationException::class, IllegalAccessException::class, IllegalArgumentException::class, InvocationTargetException::class)
    fun newInstance(vararg initargs: Any?): Any {
        return constructor.newInstance(*initargs)
    }

    fun isVarArgs(): Boolean {
        return constructor.isVarArgs
    }

    fun isSynthetic(): Boolean {
        return constructor.isSynthetic
    }

    fun <T : Annotation> getAnnotation(annotationClass: Class<T>): T {
        return constructor.getAnnotation(annotationClass)
    }

    fun getDeclaredAnnotations(): Array<Annotation> {
        return constructor.declaredAnnotations
    }

    fun getParameterAnnotations(): Array<Array<Annotation>> {
        return constructor.parameterAnnotations
    }

    fun isAccessible(): Boolean {
        @Suppress("DEPRECATION")
        return constructor.isAccessible
    }

    fun setAccessible(flag: Boolean) {
        constructor.isAccessible = flag
    }

    fun isAnnotationPresent(annotationClass: Class<out Annotation>): Boolean {
        return constructor.isAnnotationPresent(annotationClass)
    }

    fun getAnnotations(): Array<Annotation> {
        return constructor.annotations
    }

    companion object {
        @JvmStatic
        @Throws(SecurityException::class)
        fun setAccessible(array: Array<AccessibleObject>, flag: Boolean) {
            AccessibleObject.setAccessible(array, flag)
        }
    }
}
