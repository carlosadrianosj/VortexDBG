package com.vortexdbg.linux.android.dvm.jni

import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmField
import com.vortexdbg.linux.android.dvm.DvmMethod
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.Shorty
import com.vortexdbg.linux.android.dvm.VaList
import com.vortexdbg.linux.android.dvm.VarArg

import java.lang.reflect.Array
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.Arrays

internal object ProxyUtils {

    private class MethodArgs(types: List<Class<*>?>, args: List<Any?>) {
        @JvmField
        val types: kotlin.Array<Class<*>?> = types.toTypedArray()
        @JvmField
        val args: kotlin.Array<Any?> = args.toTypedArray()
    }

    private fun parseMethodArgs(dvmMethod: DvmMethod, varArg: VarArg, classLoader: ClassLoader?): MethodArgs {
        val shorties = dvmMethod.decodeArgsShorty()
        val types: MutableList<Class<*>?> = ArrayList(shorties.size)
        val args: MutableList<Any?> = ArrayList(shorties.size)
        for (i in shorties.indices) {
            val shorty = shorties[i]
            when (shorty.getType()) {
                'B' -> {
                    types.add(java.lang.Byte.TYPE)
                    args.add(varArg.getIntArg(i).toByte())
                }
                'C' -> {
                    types.add(Character.TYPE)
                    args.add(varArg.getIntArg(i).toChar())
                }
                'I' -> {
                    types.add(Integer.TYPE)
                    args.add(varArg.getIntArg(i))
                }
                'S' -> {
                    types.add(java.lang.Short.TYPE)
                    args.add(varArg.getIntArg(i).toShort())
                }
                'Z' -> {
                    types.add(java.lang.Boolean.TYPE)
                    val value = varArg.getIntArg(i)
                    args.add(BaseVM.valueOf(value))
                }
                'F' -> {
                    types.add(java.lang.Float.TYPE)
                    args.add(varArg.getFloatArg(i))
                }
                'L' -> {
                    val dvmObject = varArg.getObjectArg<DvmObject<*>?>(i)
                    if (dvmObject == null) {
                        types.add(shorty.decodeType(classLoader))
                        args.add(null)
                    } else {
                        val obj = unpack(dvmObject)
                        types.add(obj!!.javaClass)
                        args.add(obj)
                    }
                }
                'D' -> {
                    types.add(java.lang.Double.TYPE)
                    args.add(varArg.getDoubleArg(i))
                }
                'J' -> {
                    types.add(java.lang.Long.TYPE)
                    args.add(varArg.getLongArg(i))
                }
                else -> throw IllegalStateException("c=" + shorty.getType())
            }
        }
        return MethodArgs(types, args)
    }

    private fun unpack(dvmObject: DvmObject<*>?): Any? {
        if (dvmObject == null) {
            return null
        }
        val obj = dvmObject.getValue() ?: throw UnsupportedOperationException("dvmObject=$dvmObject")
        if (obj is DvmObject<*>) {
            return unpack(obj)
        } else {
            val clazz: Class<*> = obj.javaClass
            if (clazz.isArray && DvmObject::class.java.isAssignableFrom(clazz.componentType)) {
                val dvmArray = obj as kotlin.Array<*>
                val array = arrayOfNulls<Any?>(dvmArray.size)
                var arrayType: Class<*>? = null
                var oneArrayType = false
                for (i in dvmArray.indices) {
                    val dvm = dvmArray[i] as DvmObject<*>?
                    array[i] = unpack(dvm)
                    if (array[i] == null) {
                        continue
                    }
                    if (arrayType == null) {
                        arrayType = array[i]!!.javaClass
                        oneArrayType = true
                    } else if (arrayType != array[i]!!.javaClass) {
                        oneArrayType = false
                    }
                }
                if (oneArrayType) {
                    val oneArray = Array.newInstance(arrayType!!, array.size)
                    for (i in array.indices) {
                        Array.set(oneArray, i, array[i])
                    }
                    return oneArray
                }
                return array
            }

            return obj
        }
    }

    @JvmStatic
    fun parseMethodArgs(dvmMethod: DvmMethod, classes: MutableList<Class<*>?>, classLoader: ClassLoader?) {
        val shorties = dvmMethod.decodeArgsShorty()
        for (shorty in shorties) {
            val clazz = shorty.decodeType(classLoader)
            classes.add(clazz)
        }
    }

    private fun matchesTypes(parameterTypes: kotlin.Array<Class<*>>, types: kotlin.Array<Class<*>?>, strict: Boolean): Boolean {
        if (parameterTypes.size != types.size) {
            return false
        }
        for (i in types.indices) {
            if (types[i] == null) {
                continue
            }

            if (strict) {
                if (parameterTypes[i] != types[i]) {
                    return false
                }
            } else {
                if (!parameterTypes[i].isAssignableFrom(types[i])) {
                    return false
                }
            }
        }
        return true
    }

    @JvmStatic
    @Throws(NoSuchMethodException::class)
    fun matchMethodTypes(clazz: Class<*>, methodName: String, types: kotlin.Array<Class<*>?>, isStatic: Boolean): Member {
        val methods: MutableList<Method> = ArrayList()
        if (isStatic) {
            for (method in clazz.methods) {
                if (method.parameterTypes.size == types.size &&
                    methodName == method.name &&
                    Modifier.isStatic(method.modifiers)
                ) {
                    methods.add(method)
                }
            }
        }
        for (method in clazz.declaredMethods) {
            if (method.parameterTypes.size == types.size &&
                methodName == method.name &&
                isStatic == Modifier.isStatic(method.modifiers)
            ) {
                methods.add(method)
            }
        }
        if (!isStatic) {
            for (method in clazz.declaredMethods) {
                if (method.parameterTypes.size == types.size &&
                    methodName == method.name &&
                    Modifier.isStatic(method.modifiers)
                ) {
                    methods.add(method)
                }
            }
        }
        for (method in methods) {
            if (matchesTypes(method.parameterTypes, types, true)) {
                return method
            }
        }
        for (method in methods) {
            if (matchesTypes(method.parameterTypes, types, false)) {
                return method
            }
        }

        if ("<init>" == methodName) {
            for (constructor in clazz.declaredConstructors) {
                if (matchesTypes(constructor.parameterTypes, types, true)) {
                    return constructor
                }
            }
        }

        val parentClass = clazz.superclass
        if (!isStatic && parentClass != null) {
            try {
                return matchMethodTypes(parentClass, methodName, types, false)
            } catch (ignored: NoSuchMethodException) {
            }
        }

        throw NoSuchMethodException(clazz.name + "." + methodName + Arrays.toString(types))
    }

    @Throws(NoSuchMethodException::class)
    private fun matchConstructorTypes(clazz: Class<*>, types: kotlin.Array<Class<*>?>): Constructor<*> {
        for (constructor in clazz.declaredConstructors) {
            if (matchesTypes(constructor.parameterTypes, types, true)) {
                return constructor
            }
        }
        for (constructor in clazz.declaredConstructors) {
            if (matchesTypes(constructor.parameterTypes, types, false)) {
                return constructor
            }
        }
        throw NoSuchMethodException(clazz.name + ".<init>" + Arrays.toString(types))
    }

    @JvmStatic
    @Throws(NoSuchMethodException::class)
    fun findAllocConstructor(clazz: Class<*>, visitor: ProxyDvmObjectVisitor?): ProxyCall {
        val constructor = matchConstructorTypes(clazz, arrayOfNulls(0))
        return ProxyConstructor(visitor, constructor, arrayOfNulls(0))
    }

    @JvmStatic
    @Throws(NoSuchMethodException::class)
    fun findConstructor(clazz: Class<*>, dvmMethod: DvmMethod, varArg: VarArg, visitor: ProxyDvmObjectVisitor?): ProxyCall {
        if ("<init>" != dvmMethod.getMethodName()) {
            throw IllegalStateException(dvmMethod.getMethodName())
        }
        val methodArgs = parseMethodArgs(dvmMethod, varArg, clazz.classLoader)
        if (dvmMethod.member != null) {
            return ProxyConstructor(visitor, dvmMethod.member as Constructor<*>, methodArgs.args)
        }
        val constructor = matchConstructorTypes(clazz, methodArgs.types)
        dvmMethod.setMember(constructor)
        return ProxyConstructor(visitor, constructor, methodArgs.args)
    }

    @JvmStatic
    @Throws(NoSuchMethodException::class)
    fun findMethod(clazz: Class<*>, dvmMethod: DvmMethod, varArg: VarArg, isStatic: Boolean, visitor: ProxyDvmObjectVisitor?): ProxyCall {
        val methodArgs = parseMethodArgs(dvmMethod, varArg, clazz.classLoader)
        if (dvmMethod.member != null) {
            return ProxyMethod(visitor, dvmMethod.member!!, methodArgs.args)
        }
        val method = matchMethodTypes(clazz, dvmMethod.getMethodName(), methodArgs.types, isStatic)
        dvmMethod.setMember(method)
        return ProxyMethod(visitor, method, methodArgs.args)
    }

    @JvmStatic
    @Throws(NoSuchMethodException::class)
    fun findMethod(clazz: Class<*>, dvmMethod: DvmMethod, vaList: VaList, isStatic: Boolean, visitor: ProxyDvmObjectVisitor?): ProxyCall {
        val methodArgs = parseMethodArgs(dvmMethod, vaList, clazz.classLoader)
        if (dvmMethod.member != null) {
            return ProxyMethod(visitor, dvmMethod.member!!, methodArgs.args)
        }
        val method = matchMethodTypes(clazz, dvmMethod.getMethodName(), methodArgs.types, isStatic)
        dvmMethod.setMember(method)
        return ProxyMethod(visitor, method, methodArgs.args)
    }

    @JvmStatic
    @Throws(NoSuchFieldException::class)
    fun matchField(clazz: Class<*>, fieldName: String, fieldType: Class<*>?, isStatic: Boolean): Field {
        val fields: MutableList<Field> = ArrayList()
        if (isStatic) {
            for (field in clazz.fields) {
                if (fieldName == field.name &&
                    Modifier.isStatic(field.modifiers)
                ) {
                    fields.add(field)
                }
            }
        }
        for (field in clazz.declaredFields) {
            if (fieldName == field.name &&
                isStatic == Modifier.isStatic(field.modifiers)
            ) {
                fields.add(field)
            }
        }
        if (!isStatic) {
            for (field in clazz.declaredFields) {
                if (fieldName == field.name &&
                    Modifier.isStatic(field.modifiers)
                ) {
                    fields.add(field)
                }
            }
        }
        for (field in fields) {
            if (matchesTypes(arrayOf(field.type), arrayOf<Class<*>?>(fieldType), true)) {
                return field
            }
        }
        for (field in fields) {
            if (matchesTypes(arrayOf(field.type), arrayOf<Class<*>?>(fieldType), false)) {
                return field
            }
        }

        val parentClass = clazz.superclass
        if (!isStatic && parentClass != null) {
            try {
                return matchField(parentClass, fieldName, fieldType, false)
            } catch (ignored: NoSuchFieldException) {
            }
        }

        throw NoSuchFieldException(clazz.name + "." + fieldName + ":" + fieldType)
    }

    @JvmStatic
    @Throws(NoSuchFieldException::class)
    fun findField(clazz: Class<*>, dvmField: DvmField, visitor: ProxyDvmObjectVisitor?): ProxyField {
        if (dvmField.filed != null) {
            return ProxyField(visitor, dvmField.filed!!)
        }

        val shorty = dvmField.decodeShorty()
        val field = matchField(clazz, dvmField.getFieldName(), shorty.decodeType(clazz.classLoader), dvmField.isStatic())
        dvmField.setFiled(field)
        return ProxyField(visitor, field)
    }
}
