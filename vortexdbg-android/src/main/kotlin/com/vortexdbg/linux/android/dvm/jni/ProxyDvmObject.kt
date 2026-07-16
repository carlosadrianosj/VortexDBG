package com.vortexdbg.linux.android.dvm.jni

import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.StringObject
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.linux.android.dvm.array.ArrayObject
import com.vortexdbg.linux.android.dvm.array.ByteArray
import com.vortexdbg.linux.android.dvm.array.CharArray
import com.vortexdbg.linux.android.dvm.array.DoubleArray
import com.vortexdbg.linux.android.dvm.array.FloatArray
import com.vortexdbg.linux.android.dvm.array.IntArray
import com.vortexdbg.linux.android.dvm.array.ShortArray

class ProxyDvmObject private constructor(vm: VM, value: Any?) :
    DvmObject<Any?>(getObjectType(vm, value!!.javaClass), value) {

    companion object {

        private fun getObjectType(vm: VM, clazz: Class<*>): DvmClass {
            val superClass = clazz.superclass
            val interfaces = arrayOfNulls<DvmClass>(clazz.interfaces.size + (if (superClass == null) 0 else 1))
            var i = 0
            if (superClass != null) {
                interfaces[i++] = getObjectType(vm, superClass)
            }
            for (cc in clazz.interfaces) {
                interfaces[i++] = getObjectType(vm, cc)
            }
            @Suppress("UNCHECKED_CAST")
            return vm.resolveClass(clazz.name.replace('.', '/'), *(interfaces as Array<DvmClass>))
        }

        /**
         * Wraps a host JVM value as the DVM object the emulated code expects.
         *
         * Primitives arrays, strings and already-DVM values are mapped to their
         * dedicated representations; anything else is exposed through a generic
         * [ProxyDvmObject] whose DVM class mirrors the host class hierarchy.
         * Returns null only when [value] is null.
         */
        @JvmStatic
        fun createObject(vm: VM, value: Any?): DvmObject<*>? {
            if (value == null) {
                return null
            }
            if (value is Class<*>) {
                return getObjectType(vm, value)
            }
            if (value is DvmObject<*>) {
                return value
            }

            if (value is kotlin.ByteArray) {
                return ByteArray(vm, value)
            }
            if (value is kotlin.ShortArray) {
                return ShortArray(vm, value)
            }
            if (value is kotlin.CharArray) {
                return CharArray(vm, value)
            }
            if (value is kotlin.IntArray) {
                return IntArray(vm, value)
            }
            if (value is kotlin.FloatArray) {
                return FloatArray(vm, value)
            }
            if (value is kotlin.DoubleArray) {
                return DoubleArray(vm, value)
            }
            if (value is String) {
                return StringObject(vm, value)
            }
            val clazz: Class<*> = value.javaClass
            if (clazz.isArray) {
                if (clazz.componentType.isPrimitive) {
                    throw UnsupportedOperationException(value.toString())
                }
                val array = value as Array<*>
                val dvmArray = arrayOfNulls<DvmObject<*>>(array.size)
                for (i in array.indices) {
                    dvmArray[i] = createObject(vm, array[i])
                }
                return ArrayObject(*dvmArray)
            }

            return ProxyDvmObject(vm, value)
        }
    }

}
