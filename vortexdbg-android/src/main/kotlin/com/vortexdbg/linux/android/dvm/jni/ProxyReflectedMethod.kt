package com.vortexdbg.linux.android.dvm.jni

import java.lang.reflect.AccessibleObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable

class ProxyReflectedMethod internal constructor(private val method: Method) {

    @JvmField
    var accessFlags: Int = method.modifiers

    fun getAccessFlags(): Int {
        return accessFlags
    }

    fun setAccessFlags(accessFlags: Int) {
        this.accessFlags = accessFlags
    }

    fun getMethod(): Method {
        return method
    }

    fun getDeclaringClass(): Class<*> {
        return method.declaringClass
    }

    fun getName(): String {
        return method.name
    }

    fun getModifiers(): Int {
        return method.modifiers
    }

    fun getTypeParameters(): Array<TypeVariable<Method>> {
        return method.typeParameters
    }

    fun getReturnType(): Class<*> {
        return method.returnType
    }

    fun getGenericReturnType(): Type {
        return method.genericReturnType
    }

    fun getParameterTypes(): Array<Class<*>> {
        return method.parameterTypes
    }

    fun getGenericParameterTypes(): Array<Type> {
        return method.genericParameterTypes
    }

    fun getExceptionTypes(): Array<Class<*>> {
        return method.exceptionTypes
    }

    fun getGenericExceptionTypes(): Array<Type> {
        return method.genericExceptionTypes
    }

    fun toGenericString(): String {
        return method.toGenericString()
    }

    @Throws(IllegalAccessException::class, IllegalArgumentException::class, InvocationTargetException::class)
    fun invoke(obj: Any?, vararg args: Any?): Any? {
        return method.invoke(obj, *args)
    }

    fun isBridge(): Boolean {
        return method.isBridge
    }

    fun isVarArgs(): Boolean {
        return method.isVarArgs
    }

    fun isSynthetic(): Boolean {
        return method.isSynthetic
    }

    fun getDefaultValue(): Any? {
        return method.defaultValue
    }

    fun <T : Annotation> getAnnotation(annotationClass: Class<T>): T {
        return method.getAnnotation(annotationClass)
    }

    fun getDeclaredAnnotations(): Array<Annotation> {
        return method.declaredAnnotations
    }

    fun getParameterAnnotations(): Array<Array<Annotation>> {
        return method.parameterAnnotations
    }

    fun isAccessible(): Boolean {
        @Suppress("DEPRECATION")
        return method.isAccessible
    }

    fun setAccessible(flag: Boolean) {
        method.isAccessible = flag
    }

    fun isAnnotationPresent(annotationClass: Class<out Annotation>): Boolean {
        return method.isAnnotationPresent(annotationClass)
    }

    fun getAnnotations(): Array<Annotation> {
        return method.annotations
    }

    companion object {
        @JvmStatic
        @Throws(SecurityException::class)
        fun setAccessible(array: Array<AccessibleObject>, flag: Boolean) {
            AccessibleObject.setAccessible(array, flag)
        }
    }
}
