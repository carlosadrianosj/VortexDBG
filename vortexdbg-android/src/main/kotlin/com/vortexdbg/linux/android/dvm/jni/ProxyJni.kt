package com.vortexdbg.linux.android.dvm.jni

import com.vortexdbg.linux.android.dvm.BaseVM
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmField
import com.vortexdbg.linux.android.dvm.DvmMethod
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.Jni
import com.vortexdbg.linux.android.dvm.JniFunction
import com.vortexdbg.linux.android.dvm.VaList
import com.vortexdbg.linux.android.dvm.VarArg
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.ArrayList

internal open class ProxyJni internal constructor(
    private val classLoader: ProxyClassLoader,
    private val visitor: ProxyDvmObjectVisitor?,
    fallbackJni: Jni?
) : JniFunction(fallbackJni) {

    override fun allocObject(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*> {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findAllocConstructor(clazz, visitor)
            val obj = proxyCall.call(vm, null)
            return ProxyDvmObject.createObject(vm, obj)!!
        } catch (e: ClassNotFoundException) {
            log.warn("allocObject", e)
        } catch (e: IllegalAccessException) {
            log.warn("allocObject", e)
        } catch (e: InvocationTargetException) {
            log.warn("allocObject", e)
        } catch (e: InstantiationException) {
            log.warn("allocObject", e)
        } catch (e: NoSuchMethodException) {
            log.warn("allocObject", e)
        }
        return super.allocObject(vm, dvmClass, signature)
    }

    override fun newObject(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findConstructor(clazz, dvmMethod, varArg, visitor)
            val obj = proxyCall.call(vm, null)
            return ProxyDvmObject.createObject(vm, obj)!!
        } catch (e: ClassNotFoundException) {
            log.warn("newObject", e)
        } catch (e: IllegalAccessException) {
            log.warn("newObject", e)
        } catch (e: InvocationTargetException) {
            log.warn("newObject", e)
        } catch (e: InstantiationException) {
            log.warn("newObject", e)
        } catch (e: NoSuchMethodException) {
            log.warn("newObject", e)
        }

        return super.newObject(vm, dvmClass, dvmMethod, varArg)
    }

    override fun newObjectV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*> {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findConstructor(clazz, dvmMethod, vaList, visitor)
            val obj = proxyCall.call(vm, null)
            return ProxyDvmObject.createObject(vm, obj)!!
        } catch (e: ClassNotFoundException) {
            log.warn("newObjectV", e)
        } catch (e: IllegalAccessException) {
            log.warn("newObjectV", e)
        } catch (e: InvocationTargetException) {
            log.warn("newObjectV", e)
        } catch (e: InstantiationException) {
            log.warn("newObjectV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("newObjectV", e)
        }

        return super.newObjectV(vm, dvmClass, dvmMethod, vaList)
    }

    override fun callStaticFloatMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Float {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor)
            return proxyCall.call(vm, null) as Float
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticFloatMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticFloatMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticFloatMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticFloatMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticFloatMethod", e)
        }
        return super.callStaticFloatMethod(vm, dvmClass, dvmMethod, varArg)
    }

    override fun callStaticDoubleMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Double {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor)
            return proxyCall.call(vm, null) as Double
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticDoubleMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticDoubleMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticDoubleMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticDoubleMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticDoubleMethod", e)
        }
        return super.callStaticDoubleMethod(vm, dvmClass, dvmMethod, varArg)
    }

    override fun callStaticVoidMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg) {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor)
            proxyCall.call(vm, null)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticVoidMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticVoidMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticVoidMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticVoidMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticVoidMethod", e)
        }
        super.callStaticVoidMethod(vm, dvmClass, dvmMethod, varArg)
    }

    override fun callStaticVoidMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList) {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, true, visitor)
            proxyCall.call(vm, null)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticVoidMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticVoidMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticVoidMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticVoidMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticVoidMethodV", e)
        }
        super.callStaticVoidMethodV(vm, dvmClass, dvmMethod, vaList)
    }

    override fun callStaticBooleanMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Boolean {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor)
            val obj = proxyCall.call(vm, null)
            return obj as Boolean
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticBooleanMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticBooleanMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticBooleanMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticBooleanMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticBooleanMethod", e)
        }
        return super.callStaticBooleanMethod(vm, dvmClass, dvmMethod, varArg)
    }

    override fun callStaticBooleanMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Boolean {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, true, visitor)
            val obj = proxyCall.call(vm, null)
            return obj as Boolean
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticBooleanMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticBooleanMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticBooleanMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticBooleanMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticBooleanMethodV", e)
        }
        return super.callStaticBooleanMethodV(vm, dvmClass, dvmMethod, vaList)
    }

    override fun callStaticIntMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Int {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor)
            val obj = proxyCall.call(vm, null)
            return obj as Int
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticIntMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticIntMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticIntMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticIntMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticIntMethod", e)
        }
        return super.callStaticIntMethod(vm, dvmClass, dvmMethod, varArg)
    }

    override fun callStaticIntMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Int {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, true, visitor)
            val obj = proxyCall.call(vm, null)
            return obj as Int
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticIntMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticIntMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticIntMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticIntMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticIntMethodV", e)
        }
        return super.callStaticIntMethodV(vm, dvmClass, dvmMethod, vaList)
    }

    override fun callStaticLongMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Long {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor)
            val obj = proxyCall.call(vm, null)
            return obj as Long
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticLongMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticLongMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticLongMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticLongMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticLongMethod", e)
        }
        return super.callStaticLongMethod(vm, dvmClass, dvmMethod, varArg)
    }

    override fun callStaticLongMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Long {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, true, visitor)
            val obj = proxyCall.call(vm, null)
            return obj as Long
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticLongMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticLongMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticLongMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticLongMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticLongMethodV", e)
        }
        return super.callStaticLongMethodV(vm, dvmClass, dvmMethod, vaList)
    }

    override fun callStaticObjectMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, true, visitor)
            val obj = proxyCall.call(vm, null)
            return ProxyDvmObject.createObject(vm, obj)!!
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticObjectMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticObjectMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticObjectMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticObjectMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticObjectMethod", e)
        }
        return super.callStaticObjectMethod(vm, dvmClass, dvmMethod, varArg)
    }

    override fun callStaticObjectMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*> {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, true, visitor)
            val obj = proxyCall.call(vm, null)
            return ProxyDvmObject.createObject(vm, obj)!!
        } catch (e: ClassNotFoundException) {
            log.warn("callStaticObjectMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callStaticObjectMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callStaticObjectMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callStaticObjectMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callStaticObjectMethodV", e)
        }
        return super.callStaticObjectMethodV(vm, dvmClass, dvmMethod, vaList)
    }

    override fun callVoidMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg) {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            proxyCall.call(vm, thisObj)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("callVoidMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callVoidMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callVoidMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callVoidMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callVoidMethod", e)
        }
        super.callVoidMethod(vm, dvmObject, dvmMethod, varArg)
    }

    override fun callVoidMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList) {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            proxyCall.call(vm, thisObj)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("callVoidMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callVoidMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callVoidMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callVoidMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callVoidMethodV", e)
        }
        super.callVoidMethodV(vm, dvmObject, dvmMethod, vaList)
    }

    override fun callBooleanMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Boolean {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return obj as Boolean
        } catch (e: ClassNotFoundException) {
            log.warn("callBooleanMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callBooleanMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callBooleanMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callBooleanMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callBooleanMethod", e)
        }
        return super.callBooleanMethod(vm, dvmObject, dvmMethod, varArg)
    }

    override fun callBooleanMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Boolean {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return obj as Boolean
        } catch (e: ClassNotFoundException) {
            log.warn("callBooleanMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callBooleanMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callBooleanMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callBooleanMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callBooleanMethodV", e)
        }
        return super.callBooleanMethodV(vm, dvmObject, dvmMethod, vaList)
    }

    override fun callIntMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Int {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return obj as Int
        } catch (e: ClassNotFoundException) {
            log.warn("callIntMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callIntMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callIntMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callIntMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callIntMethod", e)
        }
        return super.callIntMethod(vm, dvmObject, dvmMethod, varArg)
    }

    override fun callDoubleMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Double {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return obj as Double
        } catch (e: ClassNotFoundException) {
            log.warn("callDoubleMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callDoubleMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callDoubleMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callDoubleMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callDoubleMethod", e)
        }
        return super.callDoubleMethod(vm, dvmObject, dvmMethod, varArg)
    }

    override fun callByteMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Byte {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return obj as Byte
        } catch (e: ClassNotFoundException) {
            log.warn("callByteMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callByteMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callByteMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callByteMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callByteMethodV", e)
        }
        return super.callByteMethodV(vm, dvmObject, dvmMethod, vaList)
    }

    override fun callShortMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Short {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return obj as Short
        } catch (e: ClassNotFoundException) {
            log.warn("callShortMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callShortMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callShortMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callShortMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callShortMethodV", e)
        }
        return super.callShortMethodV(vm, dvmObject, dvmMethod, vaList)
    }

    override fun callIntMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Int {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return obj as Int
        } catch (e: ClassNotFoundException) {
            log.warn("callIntMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callIntMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callIntMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callIntMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callIntMethodV", e)
        }
        return super.callIntMethodV(vm, dvmObject, dvmMethod, vaList)
    }

    override fun callObjectMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return ProxyDvmObject.createObject(vm, obj)!!
        } catch (e: ClassNotFoundException) {
            log.warn("callObjectMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callObjectMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callObjectMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callObjectMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callObjectMethod", e)
        }
        return super.callObjectMethod(vm, dvmObject, dvmMethod, varArg)
    }

    override fun callObjectMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*> {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return ProxyDvmObject.createObject(vm, obj)!!
        } catch (e: ClassNotFoundException) {
            log.warn("callObjectMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callObjectMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callObjectMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callObjectMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callObjectMethodV", e)
        }

        return super.callObjectMethodV(vm, dvmObject, dvmMethod, vaList)
    }

    override fun callLongMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Long {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, varArg, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return obj as Long
        } catch (e: ClassNotFoundException) {
            log.warn("callLongMethod", e)
        } catch (e: IllegalAccessException) {
            log.warn("callLongMethod", e)
        } catch (e: InvocationTargetException) {
            log.warn("callLongMethod", e)
        } catch (e: InstantiationException) {
            log.warn("callLongMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callLongMethod", e)
        }
        return super.callLongMethod(vm, dvmObject, dvmMethod, varArg)
    }

    override fun callLongMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Long {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return obj as Long
        } catch (e: ClassNotFoundException) {
            log.warn("callLongMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callLongMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callLongMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callLongMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callLongMethodV", e)
        }
        return super.callLongMethodV(vm, dvmObject, dvmMethod, vaList)
    }

    override fun callCharMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Char {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return obj as Char
        } catch (e: ClassNotFoundException) {
            log.warn("callCharMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callCharMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callCharMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callCharMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callCharMethodV", e)
        }
        return super.callCharMethodV(vm, dvmObject, dvmMethod, vaList)
    }

    override fun callFloatMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Float {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val proxyCall = ProxyUtils.findMethod(clazz, dvmMethod, vaList, false, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = proxyCall.call(vm, thisObj)
            return obj as Float
        } catch (e: ClassNotFoundException) {
            log.warn("callFloatMethodV", e)
        } catch (e: IllegalAccessException) {
            log.warn("callFloatMethodV", e)
        } catch (e: InvocationTargetException) {
            log.warn("callFloatMethodV", e)
        } catch (e: InstantiationException) {
            log.warn("callFloatMethodV", e)
        } catch (e: NoSuchMethodException) {
            log.warn("callFloatMethodV", e)
        }
        return super.callFloatMethodV(vm, dvmObject, dvmMethod, vaList)
    }

    override fun toReflectedMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod): DvmObject<*> {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val classes: MutableList<Class<*>?> = ArrayList(10)
            ProxyUtils.parseMethodArgs(dvmMethod, classes, clazz.classLoader)
            val types = classes.toTypedArray()
            val method = ProxyUtils.matchMethodTypes(clazz, dvmMethod.getMethodName(), types, dvmMethod.isStatic())
            return if (method is Method) {
                ProxyDvmObject.createObject(vm, ProxyReflectedMethod(method))!!
            } else {
                ProxyDvmObject.createObject(vm, ProxyReflectedConstructor(method as Constructor<*>))!!
            }
        } catch (e: ClassNotFoundException) {
            log.warn("toReflectedMethod", e)
        } catch (e: NoSuchMethodException) {
            log.warn("toReflectedMethod", e)
        }

        return super.toReflectedMethod(vm, dvmClass, dvmMethod)
    }

    override fun getObjectField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): DvmObject<*> {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            val obj = field.get(thisObj)
            return ProxyDvmObject.createObject(vm, obj)!!
        } catch (e: ClassNotFoundException) {
            log.warn("getObjectField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("getObjectField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("getObjectField: {}", dvmField, e)
        }

        return super.getObjectField(vm, dvmObject, dvmField)
    }

    override fun getLongField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Long {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            return field.getLong(thisObj)
        } catch (e: ClassNotFoundException) {
            log.warn("getLongField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("getLongField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("getLongField: {}", dvmField, e)
        }

        return super.getLongField(vm, dvmObject, dvmField)
    }

    override fun getFloatField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Float {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            return field.getFloat(thisObj)
        } catch (e: ClassNotFoundException) {
            log.warn("getFloatField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("getFloatField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("getFloatField: {}", dvmField, e)
        }

        return super.getFloatField(vm, dvmObject, dvmField)
    }

    override fun getBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Boolean {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            return field.getBoolean(thisObj)
        } catch (e: ClassNotFoundException) {
            log.warn("getBooleanField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("getBooleanField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("getBooleanField: {}", dvmField, e)
        }
        return super.getBooleanField(vm, dvmObject, dvmField)
    }

    override fun getByteField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Byte {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            return field.getByte(thisObj)
        } catch (e: ClassNotFoundException) {
            log.warn("getByteField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("getByteField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("getByteField: {}", dvmField, e)
        }
        return super.getByteField(vm, dvmObject, dvmField)
    }

    override fun getIntField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Int {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            return field.getInt(thisObj)
        } catch (e: ClassNotFoundException) {
            log.warn("getIntField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("getIntField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("getIntField: {}", dvmField, e)
        }
        return super.getIntField(vm, dvmObject, dvmField)
    }

    override fun setIntField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Int) {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            field.setInt(thisObj, value)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setIntField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("setIntField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("setIntField: {}", dvmField, e)
        }
        super.setIntField(vm, dvmObject, dvmField, value)
    }

    override fun setFloatField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Float) {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            field.setFloat(thisObj, value)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setFloatField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("setFloatField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("setFloatField: {}", dvmField, e)
        }
        super.setFloatField(vm, dvmObject, dvmField, value)
    }

    override fun setDoubleField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Double) {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            field.setDouble(thisObj, value)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setDoubleField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("setDoubleField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("setDoubleField: {}", dvmField, e)
        }
        super.setDoubleField(vm, dvmObject, dvmField, value)
    }

    override fun setLongField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Long) {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            field.setLong(thisObj, value)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setLongField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("setLongField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("setLongField: {}", dvmField, e)
        }
        super.setLongField(vm, dvmObject, dvmField, value)
    }

    override fun setBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Boolean) {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            field.setBoolean(thisObj, value)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setBooleanField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("setBooleanField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("setBooleanField: {}", dvmField, e)
        }
        super.setBooleanField(vm, dvmObject, dvmField, value)
    }

    override fun setObjectField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: DvmObject<*>) {
        try {
            val clazz = classLoader.loadClass(dvmObject.getObjectType()!!.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val thisObj = dvmObject.getValue()
            if (thisObj == null) {
                throw IllegalStateException("obj is null: $dvmObject")
            }
            field.setObject(thisObj, value.getValue())
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setObjectField: {}", dvmField, e)
        } catch (e: NoSuchFieldException) {
            log.warn("setObjectField: {}", dvmField, e)
        } catch (e: IllegalAccessException) {
            log.warn("setObjectField: {}", dvmField, e)
        }
        super.setObjectField(vm, dvmObject, dvmField, value)
    }

    override fun getStaticObjectField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): DvmObject<*> {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            val obj = field.get(null)
            return ProxyDvmObject.createObject(vm, obj)!!
        } catch (e: ClassNotFoundException) {
            log.warn("getStaticObjectField", e)
        } catch (e: NoSuchFieldException) {
            log.warn("getStaticObjectField", e)
        } catch (e: IllegalAccessException) {
            log.warn("getStaticObjectField", e)
        }

        return super.getStaticObjectField(vm, dvmClass, dvmField)
    }

    override fun getStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Boolean {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            return field.getBoolean(null)
        } catch (e: ClassNotFoundException) {
            log.warn("getStaticBooleanField", e)
        } catch (e: NoSuchFieldException) {
            log.warn("getStaticBooleanField", e)
        } catch (e: IllegalAccessException) {
            log.warn("getStaticBooleanField", e)
        }

        return super.getStaticBooleanField(vm, dvmClass, dvmField)
    }

    override fun getStaticByteField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Byte {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            return field.getByte(null)
        } catch (e: ClassNotFoundException) {
            log.warn("getStaticByteField", e)
        } catch (e: NoSuchFieldException) {
            log.warn("getStaticByteField", e)
        } catch (e: IllegalAccessException) {
            log.warn("getStaticByteField", e)
        }
        return super.getStaticByteField(vm, dvmClass, dvmField)
    }

    override fun getStaticIntField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Int {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            return field.getInt(null)
        } catch (e: ClassNotFoundException) {
            log.warn("getStaticIntField", e)
        } catch (e: NoSuchFieldException) {
            log.warn("getStaticIntField", e)
        } catch (e: IllegalAccessException) {
            log.warn("getStaticIntField", e)
        }

        return super.getStaticIntField(vm, dvmClass, dvmField)
    }

    override fun getStaticLongField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Long {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            return field.getLong(null)
        } catch (e: ClassNotFoundException) {
            log.warn("getStaticLongField", e)
        } catch (e: NoSuchFieldException) {
            log.warn("getStaticLongField", e)
        } catch (e: IllegalAccessException) {
            log.warn("getStaticLongField", e)
        }
        return super.getStaticLongField(vm, dvmClass, dvmField)
    }

    override fun setStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Boolean) {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            field.setBoolean(null, value)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setStaticBooleanField", e)
        } catch (e: NoSuchFieldException) {
            log.warn("setStaticBooleanField", e)
        } catch (e: IllegalAccessException) {
            log.warn("setStaticBooleanField", e)
        }
        super.setStaticBooleanField(vm, dvmClass, dvmField, value)
    }

    override fun setStaticIntField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Int) {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            field.setInt(null, value)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setStaticIntField", e)
        } catch (e: NoSuchFieldException) {
            log.warn("setStaticIntField", e)
        } catch (e: IllegalAccessException) {
            log.warn("setStaticIntField", e)
        }
        super.setStaticIntField(vm, dvmClass, dvmField, value)
    }

    override fun setStaticObjectField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: DvmObject<*>) {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            field.setObject(null, value.getValue())
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setStaticObjectField", e)
        } catch (e: NoSuchFieldException) {
            log.warn("setStaticObjectField", e)
        } catch (e: IllegalAccessException) {
            log.warn("setStaticObjectField", e)
        }
        super.setStaticObjectField(vm, dvmClass, dvmField, value)
    }

    override fun setStaticLongField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Long) {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            field.setLong(null, value)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setStaticLongField", e)
        } catch (e: NoSuchFieldException) {
            log.warn("setStaticLongField", e)
        } catch (e: IllegalAccessException) {
            log.warn("setStaticLongField", e)
        }
        super.setStaticLongField(vm, dvmClass, dvmField, value)
    }

    override fun setStaticFloatField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Float) {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            field.setFloat(null, value)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setStaticFloatField", e)
        } catch (e: NoSuchFieldException) {
            log.warn("setStaticFloatField", e)
        } catch (e: IllegalAccessException) {
            log.warn("setStaticFloatField", e)
        }
        super.setStaticFloatField(vm, dvmClass, dvmField, value)
    }

    override fun setStaticDoubleField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Double) {
        try {
            val clazz = classLoader.loadClass(dvmClass.getName())
            val field = ProxyUtils.findField(clazz, dvmField, visitor)
            field.setDouble(null, value)
            return
        } catch (e: ClassNotFoundException) {
            log.warn("setStaticDoubleField", e)
        } catch (e: NoSuchFieldException) {
            log.warn("setStaticDoubleField", e)
        } catch (e: IllegalAccessException) {
            log.warn("setStaticDoubleField", e)
        }
        super.setStaticDoubleField(vm, dvmClass, dvmField, value)
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ProxyJni::class.java)
    }
}
