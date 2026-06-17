package com.vortexdbg.linux.android.dvm

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.linux.android.dvm.jni.ProxyDvmObject
import com.vortexdbg.memory.MemoryBlock
import com.vortexdbg.pointer.VortexdbgPointer
import com.sun.jna.Pointer

import java.util.ArrayList

open class DvmObject<T> private constructor(private val vm: BaseVM?, private val objectType: DvmClass?, value: T) : Hashable() {

    @JvmField
    protected var value: T = value

    protected constructor(objectType: DvmClass?, value: T) : this(if (objectType == null) null else objectType.vm, objectType, value)

    fun setValue(obj: Any?) {
        @Suppress("UNCHECKED_CAST")
        this.value = obj as T
    }

    open fun getValue(): T {
        return value
    }

    open fun getObjectType(): DvmClass? {
        return objectType
    }

    open fun isInstanceOf(dvmClass: DvmClass): Boolean {
        return objectType != null && objectType.isInstance(dvmClass)
    }

    @Suppress("unused")
    open fun callJniMethod(emulator: Emulator<*>, method: String, vararg args: Any?) {
        if (objectType == null) {
            throw IllegalStateException("objectType is null")
        }
        try {
            callJniMethod(emulator, vm!!, objectType, this, method, *args)
        } finally {
            vm!!.deleteLocalRefs()
        }
    }

    @Suppress("unused")
    open fun callJniMethodBoolean(emulator: Emulator<*>, method: String, vararg args: Any?): Boolean {
        return BaseVM.valueOf(callJniMethodInt(emulator, method, *args))
    }

    @Suppress("unused")
    open fun callJniMethodInt(emulator: Emulator<*>, method: String, vararg args: Any?): Int {
        if (objectType == null) {
            throw IllegalStateException("objectType is null")
        }
        try {
            return callJniMethod(emulator, vm!!, objectType, this, method, *args).toInt()
        } finally {
            vm!!.deleteLocalRefs()
        }
    }

    @Suppress("unused")
    open fun callJniMethodLong(emulator: Emulator<*>, method: String, vararg args: Any?): Long {
        if (objectType == null) {
            throw IllegalStateException("objectType is null")
        }
        try {
            return callJniMethod(emulator, vm!!, objectType, this, method, *args).toLong()
        } finally {
            vm!!.deleteLocalRefs()
        }
    }

    @Suppress("unused")
    open fun <V : DvmObject<*>> callJniMethodObject(emulator: Emulator<*>, method: String, vararg args: Any?): V {
        if (objectType == null) {
            throw IllegalStateException("objectType is null")
        }
        try {
            val number = callJniMethod(emulator, vm!!, objectType, this, method, *args)
            return objectType.vm.getObject(number.toInt())
        } finally {
            vm!!.deleteLocalRefs()
        }
    }

    override fun toString(): String {
        if (value is Enum<*>) {
            return value.toString()
        }

        if (objectType == null) {
            return javaClass.simpleName + "{" +
                    "value=" + value +
                    '}'
        }

        return objectType.getName() + "@" + Integer.toHexString(hashCode())
    }

    @JvmField
    protected var memoryBlock: MemoryBlock? = null

    fun allocateMemoryBlock(emulator: Emulator<*>, length: Int): VortexdbgPointer {
        if (memoryBlock == null) {
            memoryBlock = emulator.getMemory().malloc(length, true)
        }
        return memoryBlock!!.getPointer()
    }

    fun freeMemoryBlock(pointer: Pointer?) {
        if (this.memoryBlock != null && (pointer == null || this.memoryBlock!!.isSame(pointer))) {
            this.memoryBlock!!.free()
            this.memoryBlock = null
        }
    }

    fun onDeleteRef() {
        freeMemoryBlock(null)
    }

    companion object {
        internal fun newDvmObject(objectType: DvmClass, value: Any?): DvmObject<Any?> {
            return DvmObject(objectType.vm, objectType, value)
        }

        internal fun callJniMethod(emulator: Emulator<*>, vm: VM, objectType: DvmClass, thisObj: DvmObject<*>, method: String, vararg args: Any?): Number {
            val fnPtr = objectType.findNativeFunction(emulator, method)
            vm.addLocalObject(thisObj)
            val list: MutableList<Any?> = ArrayList(10)
            list.add(vm.getJNIEnv())
            list.add(thisObj.hashCode())
            if (args != null) {
                for (arg in args) {
                    if (arg is Boolean) {
                        list.add(if (arg) VM.JNI_TRUE else VM.JNI_FALSE)
                        continue
                    } else if (arg is Hashable) {
                        list.add(arg.hashCode()) // dvm object

                        if (arg is DvmObject<*>) {
                            vm.addLocalObject(arg)
                        }
                        continue
                    } else if (arg is DvmAwareObject ||
                        arg is String ||
                        arg is ByteArray ||
                        arg is ShortArray ||
                        arg is IntArray ||
                        arg is FloatArray ||
                        arg is DoubleArray ||
                        arg is Enum<*>
                    ) {
                        val obj = ProxyDvmObject.createObject(vm, arg)
                        list.add(obj.hashCode())
                        vm.addLocalObject(obj)
                        continue
                    }

                    list.add(arg)
                }
            }
            val ret = Module.emulateFunction(emulator, fnPtr.peer, *list.toTypedArray())
            // Vortex-DBG (A1 / WF3): propaga exceção JNI pendente para o host (opt-in).
            if (vm is BaseVM) {
                val baseVM = vm
                if (baseVM.exceptionPropagation && baseVM.throwable != null) {
                    val pending = baseVM.throwable
                    baseVM.throwable = null
                    throw VortexJniException(pending)
                }
            }
            return ret
        }
    }
}
