package com.vortexdbg.linux.android.dvm

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.ArrayList

open class DvmField internal constructor(
    private val dvmClass: DvmClass,
    @JvmField val fieldName: String,
    @JvmField val fieldType: String,
    private val isStatic: Boolean
) : Hashable() {

    open fun getDvmClass(): DvmClass {
        return dvmClass
    }

    open fun getFieldName(): String {
        return fieldName
    }

    open fun getFieldType(): String {
        return fieldType
    }

    open fun isStatic(): Boolean {
        return isStatic
    }

    fun getSignature(): String {
        return dvmClass.getClassName() + "->" + fieldName + ":" + fieldType
    }

    private var shortyCache: kotlin.Array<Shorty>? = null

    fun decodeShorty(): Shorty {
        if (shortyCache != null) {
            return shortyCache!![0]
        }

        val chars = getFieldType().toCharArray()
        val list: MutableList<Shorty> = ArrayList(chars.size)
        var arrayDimensions = 0
        var isType = false
        var shorty: Shorty? = null
        val binaryName = StringBuilder()
        for (i in chars.indices) {
            val c = chars[i]

            if (isType) {
                if (c == ';') {
                    isType = false
                    shorty!!.setBinaryName(binaryName.toString())
                    binaryName.delete(0, binaryName.length)
                } else {
                    binaryName.append(c)
                }
                continue
            }

            var type = '0'
            when (c) {
                'L' -> {
                    isType = true
                    type = c
                }
                'B', 'C', 'I', 'S', 'Z', 'D', 'F', 'J' -> {
                    type = c
                }
                '[' -> {
                    arrayDimensions++
                }
                else -> {
                    throw IllegalStateException("i=" + i + ", char=" + chars[i] + ", fieldType=" + fieldType)
                }
            }

            if (type == '0') {
                continue
            }

            shorty = Shorty(arrayDimensions, type)
            list.add(shorty)
            arrayDimensions = 0
        }
        shortyCache = list.toTypedArray()
        return shortyCache!![0]
    }

    fun getStaticObjectField(): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).getStaticObjectField(vm, dvmClass, this)
    }

    fun getStaticBooleanField(): Boolean {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).getStaticBooleanField(vm, dvmClass, this)
    }

    fun getStaticByteField(): Byte {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).getStaticByteField(vm, dvmClass, this)
    }

    fun getStaticIntField(): Int {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).getStaticIntField(vm, dvmClass, this)
    }

    fun getObjectField(dvmObject: DvmObject<*>): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).getObjectField(vm, dvmObject, this)
    }

    fun getByteField(dvmObject: DvmObject<*>): Byte {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).getByteField(vm, dvmObject, this)
    }

    fun getIntField(dvmObject: DvmObject<*>): Int {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).getIntField(vm, dvmObject, this)
    }

    fun getLongField(dvmObject: DvmObject<*>): Long {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).getLongField(vm, dvmObject, this)
    }

    fun getFloatField(dvmObject: DvmObject<*>): Float {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).getFloatField(vm, dvmObject, this)
    }

    fun setObjectField(dvmObject: DvmObject<*>, value: DvmObject<*>?) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setObjectField(vm, dvmObject, this, value!!)
    }

    fun getBooleanField(dvmObject: DvmObject<*>): Int {
        val vm = dvmClass.vm
        return if (checkJni(vm, dvmClass).getBooleanField(vm, dvmObject, this)) VM.JNI_TRUE else VM.JNI_FALSE
    }

    fun setIntField(dvmObject: DvmObject<*>, value: Int) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setIntField(vm, dvmObject, this, value)
    }

    fun setLongField(dvmObject: DvmObject<*>, value: Long) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setLongField(vm, dvmObject, this, value)
    }

    fun setBooleanField(dvmObject: DvmObject<*>, value: Boolean) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setBooleanField(vm, dvmObject, this, value)
    }

    fun setFloatField(dvmObject: DvmObject<*>, value: Float) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setFloatField(vm, dvmObject, this, value)
    }

    fun setDoubleField(dvmObject: DvmObject<*>, value: Double) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setDoubleField(vm, dvmObject, this, value)
    }

    fun setStaticObjectField(value: DvmObject<*>?) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setStaticObjectField(vm, dvmClass, this, value!!)
    }

    fun setStaticBooleanField(value: Boolean) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setStaticBooleanField(vm, dvmClass, this, value)
    }

    fun setStaticIntField(value: Int) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setStaticIntField(vm, dvmClass, this, value)
    }

    fun setStaticLongField(value: Long) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setStaticLongField(vm, dvmClass, this, value)
    }

    fun setStaticFloatField(value: Float) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setStaticFloatField(vm, dvmClass, this, value)
    }

    fun setStaticDoubleField(value: Double) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).setStaticDoubleField(vm, dvmClass, this, value)
    }

    fun getStaticLongField(): Long {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).getStaticLongField(vm, dvmClass, this)
    }

    @JvmField
    var filed: Field? = null

    open fun setFiled(filed: Field) {
        filed.isAccessible = true
        if (Modifier.isStatic(filed.modifiers) xor isStatic) {
            throw IllegalStateException(toString())
        }
        this.filed = filed
    }

    override fun toString(): String {
        return getSignature()
    }
}
