package com.vortexdbg.linux.android.dvm

import com.vortexdbg.Emulator
import com.vortexdbg.Symbol
import com.vortexdbg.pointer.VortexdbgPointer
import org.slf4j.LoggerFactory

import java.util.HashMap
import java.util.Objects

open class DvmClass protected constructor(
    @JvmField val vm: BaseVM,
    private val className: String,
    private val superClass: DvmClass?,
    private val interfaceClasses: kotlin.Array<DvmClass>?,
    value: Class<*>?
) : DvmObject<Class<*>?>(if (ROOT_CLASS == className) null else vm.resolveClass(ROOT_CLASS), value) {

    internal constructor(vm: BaseVM, className: String, superClass: DvmClass?, interfaceClasses: kotlin.Array<DvmClass>?) :
        this(vm, className, superClass, interfaceClasses, null)

    @Suppress("unused")
    open fun getSuperclass(): DvmClass? {
        return superClass
    }

    @Suppress("unused")
    open fun getInterfaces(): kotlin.Array<DvmClass>? {
        return interfaceClasses
    }

    override fun getObjectType(): DvmClass? {
        if (ROOT_CLASS == className) {
            return this
        }

        return super.getObjectType()
    }

    open fun getClassName(): String {
        return className
    }

    open fun getName(): String {
        return className.replace('/', '.')
    }

    open fun newObject(value: Any?): DvmObject<*> {
        return newDvmObject(this, value)
    }

    fun allocObject(): DvmObject<*> {
        val signature = this.getClassName() + "->allocObject"
        if (log.isDebugEnabled) {
            log.debug("allocObject signature={}", signature)
        }
        val vm = this.vm
        return checkJni(vm, this).allocObject(vm, this, signature)
    }

    private val staticMethodMap: MutableMap<Int, DvmMethod> = HashMap()

    fun getStaticMethod(hash: Int): DvmMethod? {
        var method = staticMethodMap[hash]
        if (method == null && superClass != null) {
            method = superClass.getStaticMethod(hash)
        }
        if (method == null) {
            for (interfaceClass in interfaceClasses!!) {
                method = interfaceClass.getStaticMethod(hash)
                if (method != null) {
                    break
                }
            }
        }
        return method
    }

    fun getStaticMethodID(methodName: String, args: String): Int {
        val signature = getClassName() + "->" + methodName + args
        val hash = vm.hash(signature)
        if (log.isDebugEnabled) {
            log.debug("getStaticMethodID signature={}, hash=0x{}", signature, java.lang.Long.toHexString(hash.toLong()))
        }
        if (checkJni(vm, this).acceptMethod(this, signature, true)) {
            if (!staticMethodMap.containsKey(hash)) {
                staticMethodMap[hash] = DvmMethod(this, methodName, args, true)
            }
            return hash
        } else {
            return 0
        }
    }

    private val methodMap: MutableMap<Int, DvmMethod> = HashMap()

    fun getMethod(hash: Int): DvmMethod? {
        var method = methodMap[hash]
        if (method == null && superClass != null) {
            method = superClass.getMethod(hash)
        }
        if (method == null) {
            for (interfaceClass in interfaceClasses!!) {
                method = interfaceClass.getMethod(hash)
                if (method != null) {
                    break
                }
            }
        }
        return method
    }

    fun getMethodID(methodName: String, args: String): Int {
        val signature = getClassName() + "->" + methodName + args
        val hash = vm.hash(signature)
        if (log.isDebugEnabled) {
            log.debug("getMethodID signature={}, hash=0x{}", signature, java.lang.Long.toHexString(hash.toLong()))
        }
        if (vm.jni == null || vm.jni!!.acceptMethod(this, signature, false)) {
            if (!methodMap.containsKey(hash)) {
                methodMap[hash] = DvmMethod(this, methodName, args, false)
            }
            return hash
        } else {
            return 0
        }
    }

    private val fieldMap: MutableMap<Int, DvmField> = HashMap()

    fun getField(hash: Int): DvmField? {
        var field = fieldMap[hash]
        if (field == null && superClass != null) {
            field = superClass.getField(hash)
        }
        if (field == null) {
            for (interfaceClass in interfaceClasses!!) {
                field = interfaceClass.getField(hash)
                if (field != null) {
                    break
                }
            }
        }
        return field
    }

    fun getFieldID(fieldName: String, fieldType: String): Int {
        val signature = getClassName() + "->" + fieldName + ":" + fieldType
        val hash = vm.hash(signature)
        if (log.isDebugEnabled) {
            log.debug("getFieldID signature={}, hash=0x{}", signature, java.lang.Long.toHexString(hash.toLong()))
        }
        if (vm.jni == null || vm.jni!!.acceptField(this, signature, false)) {
            if (!fieldMap.containsKey(hash)) {
                fieldMap[hash] = DvmField(this, fieldName, fieldType, false)
            }
            return hash
        } else {
            return 0
        }
    }

    private val staticFieldMap: MutableMap<Int, DvmField> = HashMap()

    fun getStaticField(hash: Int): DvmField? {
        var field = staticFieldMap[hash]
        if (field == null && superClass != null) {
            field = superClass.getStaticField(hash)
        }
        if (field == null) {
            for (interfaceClass in interfaceClasses!!) {
                field = interfaceClass.getStaticField(hash)
                if (field != null) {
                    break
                }
            }
        }
        return field
    }

    fun getStaticFieldID(fieldName: String, fieldType: String): Int {
        val signature = getClassName() + "->" + fieldName + ":" + fieldType
        val hash = vm.hash(signature)
        if (log.isDebugEnabled) {
            log.debug("getStaticFieldID signature={}, hash=0x{}", signature, java.lang.Long.toHexString(hash.toLong()))
        }
        if (vm.jni == null || vm.jni!!.acceptField(this, signature, true)) {
            if (!staticFieldMap.containsKey(hash)) {
                staticFieldMap[hash] = DvmField(this, fieldName, fieldType, true)
            }
            return hash
        } else {
            return 0
        }
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false
        val dvmClass = o as DvmClass
        return Objects.equals(getClassName(), dvmClass.getClassName())
    }

    override fun hashCode(): Int {
        return vm.hash(getClassName())
    }

    override fun toString(): String {
        return "class " + getClassName()
    }

    @JvmField
    val nativesMap: MutableMap<String, VortexdbgPointer> = HashMap()

    fun findNativeFunction(emulator: Emulator<*>, method: String): VortexdbgPointer {
        var fnPtr = nativesMap[method]
        var index = method.indexOf('(')
        if (fnPtr == null && index == -1) {
            index = method.length
        }
        val builder = StringBuilder()
        builder.append("Java_")
        mangleForJni(builder, getClassName())
        builder.append("_")
        mangleForJni(builder, method.substring(0, index))
        val symbolName = builder.toString()
        if (fnPtr == null) {
            for (module in emulator.getMemory().getLoadedModules()) {
                val symbol: Symbol? = module.findSymbolByName(symbolName, false)
                if (symbol != null) {
                    fnPtr = symbol.createPointer(emulator) as VortexdbgPointer
                    break
                }
            }
        }
        if (fnPtr == null) {
            throw IllegalArgumentException("find method failed: " + method)
        }
        if (vm.verbose) {
            System.out.printf("Find native function %s => %s%n", symbolName, fnPtr)
        }
        return fnPtr
    }

    fun callStaticJniMethod(emulator: Emulator<*>, method: String, vararg args: Any?) {
        try {
            callJniMethod(emulator, vm, this, this, method, *args)
        } finally {
            vm.deleteLocalRefs()
        }
    }

    @Suppress("unused")
    open fun callStaticJniMethodBoolean(emulator: Emulator<*>, method: String, vararg args: Any?): Boolean {
        return BaseVM.valueOf(callStaticJniMethodInt(emulator, method, *args))
    }

    @Suppress("unused")
    open fun callStaticJniMethodInt(emulator: Emulator<*>, method: String, vararg args: Any?): Int {
        try {
            return callJniMethod(emulator, vm, this, this, method, *args).toInt()
        } finally {
            vm.deleteLocalRefs()
        }
    }

    @Suppress("unused")
    open fun callStaticJniMethodLong(emulator: Emulator<*>, method: String, vararg args: Any?): Long {
        try {
            return callJniMethod(emulator, vm, this, this, method, *args).toLong()
        } finally {
            vm.deleteLocalRefs()
        }
    }

    @Suppress("unused")
    open fun <T : DvmObject<*>> callStaticJniMethodObject(emulator: Emulator<*>, method: String, vararg args: Any?): T {
        try {
            val number = callJniMethod(emulator, vm, this, this, method, *args)
            return vm.getObject(number.toInt())
        } finally {
            vm.deleteLocalRefs()
        }
    }

    fun isInstance(dvmClass: DvmClass): Boolean {
        if (dvmClass === this) {
            return true
        }

        for (dc in interfaceClasses!!) {
            if (dc === dvmClass) {
                return true
            }
        }
        return if (superClass != null) {
            superClass.isInstance(dvmClass)
        } else {
            false
        }
    }

    private var jni: JniFunction? = null

    protected fun setJni(jni: JniFunction) {
        this.jni = jni
    }

    fun getJni(): Jni? {
        return jni
    }

    companion object {
        private val log = LoggerFactory.getLogger(DvmClass::class.java)

        private const val ROOT_CLASS = "java/lang/Class"

        private fun mangleForJni(builder: StringBuilder, name: String) {
            val chars = name.toCharArray()
            for (c in chars) {
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                    builder.append(c)
                } else if (c == '.' || c == '/') {
                    builder.append("_")
                } else if (c == '_') {
                    builder.append("_1")
                } else if (c == ';') {
                    builder.append("_2")
                } else if (c == '[') {
                    builder.append("_3")
                } else {
                    builder.append(String.format("_0%04x", c.code and 0xffff))
                }
            }
        }
    }
}
