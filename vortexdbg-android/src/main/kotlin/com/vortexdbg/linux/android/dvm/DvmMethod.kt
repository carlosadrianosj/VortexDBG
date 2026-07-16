package com.vortexdbg.linux.android.dvm

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.UUID

open class DvmMethod internal constructor(
    private val dvmClass: DvmClass,
    @JvmField val methodName: String,
    @JvmField val args: String,
    @JvmField val isStatic: Boolean
) : Hashable() {

    open fun isConstructor(): Boolean {
        return "<init>" == methodName
    }

    open fun getDvmClass(): DvmClass {
        return dvmClass
    }

    open fun getMethodName(): String {
        // bug fix for android UUID.createString
        if (UUID::class.java.name == dvmClass.getName() && "createString" == methodName) {
            return "toString"
        }
        return methodName
    }

    open fun getArgs(): String {
        return args
    }

    fun getSignature(): String {
        return dvmClass.getClassName() + "->" + methodName + args
    }

    open fun isStatic(): Boolean {
        return isStatic
    }

    fun callStaticObjectMethod(varArg: VarArg): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticObjectMethod(vm, dvmClass, this, varArg)
    }

    fun callStaticObjectMethodV(vaList: VaList): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticObjectMethodV(vm, dvmClass, this, vaList)
    }

    fun callStaticObjectMethodA(vaList: VaList): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticObjectMethodV(vm, dvmClass, this, vaList)
    }

    fun callObjectMethod(dvmObject: DvmObject<*>, varArg: VarArg): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callObjectMethod(vm, dvmObject, this, varArg)
    }

    fun callLongMethodA(dvmObject: DvmObject<*>, vaList: VaList): Long {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callLongMethodV(vm, dvmObject, this, vaList)
    }

    fun callLongMethod(dvmObject: DvmObject<*>, varArg: VarArg): Long {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callLongMethod(vm, dvmObject, this, varArg)
    }

    fun callLongMethodV(dvmObject: DvmObject<*>, vaList: VaList): Long {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callLongMethodV(vm, dvmObject, this, vaList)
    }

    fun callObjectMethodV(dvmObject: DvmObject<*>, vaList: VaList): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callObjectMethodV(vm, dvmObject, this, vaList)
    }

    fun callObjectMethodA(dvmObject: DvmObject<*>, vaList: VaList): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callObjectMethodV(vm, dvmObject, this, vaList)
    }

    fun callByteMethodV(dvmObject: DvmObject<*>, vaList: VaList): Byte {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callByteMethodV(vm, dvmObject, this, vaList)
    }

    fun callShortMethodV(dvmObject: DvmObject<*>, vaList: VaList): Short {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callShortMethodV(vm, dvmObject, this, vaList)
    }

    fun callIntMethodV(dvmObject: DvmObject<*>, vaList: VaList): Int {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callIntMethodV(vm, dvmObject, this, vaList)
    }

    fun callBooleanMethod(dvmObject: DvmObject<*>, varArg: VarArg): Boolean {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callBooleanMethod(vm, dvmObject, this, varArg)
    }

    fun callBooleanMethodV(dvmObject: DvmObject<*>, vaList: VaList): Boolean {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callBooleanMethodV(vm, dvmObject, this, vaList)
    }

    fun callBooleanMethodA(dvmObject: DvmObject<*>, vaList: VaList): Boolean {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callBooleanMethodV(vm, dvmObject, this, vaList)
    }

    fun callIntMethod(dvmObject: DvmObject<*>, varArg: VarArg): Int {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callIntMethod(vm, dvmObject, this, varArg)
    }

    fun callIntMethodA(dvmObject: DvmObject<*>, vaList: VaList): Int {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callIntMethodV(vm, dvmObject, this, vaList)
    }

    fun callDoubleMethod(dvmObject: DvmObject<*>, varArg: VarArg): Double {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callDoubleMethod(vm, dvmObject, this, varArg)
    }

    fun callCharMethodV(dvmObject: DvmObject<*>, vaList: VaList): Char {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callCharMethodV(vm, dvmObject, this, vaList)
    }

    fun callVoidMethod(dvmObject: DvmObject<*>, varArg: VarArg) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).callVoidMethod(vm, dvmObject, this, varArg)
    }

    fun callStaticIntMethod(varArg: VarArg): Int {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticIntMethod(vm, dvmClass, this, varArg)
    }

    fun callStaticIntMethodV(vaList: VaList): Int {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticIntMethodV(vm, dvmClass, this, vaList)
    }

    fun callStaticLongMethodA(vaList: VaList): Long {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticLongMethodV(vm, dvmClass, this, vaList)
    }

    fun callStaticLongMethod(varArg: VarArg): Long {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticLongMethod(vm, dvmClass, this, varArg)
    }

    fun callStaticLongMethodV(vaList: VaList): Long {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticLongMethodV(vm, dvmClass, this, vaList)
    }

    fun CallStaticBooleanMethod(varArg: VarArg): Boolean {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticBooleanMethod(vm, dvmClass, this, varArg)
    }

    fun callStaticBooleanMethodV(vaList: VaList): Boolean {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticBooleanMethodV(vm, dvmClass, this, vaList)
    }

    fun callStaticFloatMethod(varArg: VarArg): Float {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticFloatMethod(vm, dvmClass, this, varArg)
    }

    fun callStaticDoubleMethod(varArg: VarArg): Double {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callStaticDoubleMethod(vm, dvmClass, this, varArg)
    }

    fun callStaticVoidMethod(varArg: VarArg) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).callStaticVoidMethod(vm, dvmClass, this, varArg)
    }

    fun callStaticVoidMethodV(vaList: VaList) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).callStaticVoidMethodV(vm, dvmClass, this, vaList)
    }

    fun callStaticVoidMethodA(vaList: VaList) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).callStaticVoidMethodV(vm, dvmClass, this, vaList)
    }

    fun newObjectV(vaList: VaList): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).newObjectV(vm, dvmClass, this, vaList)
    }

    fun newObjectA(vaList: VaList): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).newObjectV(vm, dvmClass, this, vaList)
    }

    fun newObject(varArg: VarArg): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).newObject(vm, dvmClass, this, varArg)
    }

    fun callVoidMethodV(dvmObject: DvmObject<*>, vaList: VaList) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).callVoidMethodV(vm, dvmObject, this, vaList)
    }

    fun callVoidMethodA(dvmObject: DvmObject<*>, vaList: VaList) {
        val vm = dvmClass.vm
        checkJni(vm, dvmClass).callVoidMethodV(vm, dvmObject, this, vaList)
    }

    fun callFloatMethodV(dvmObject: DvmObject<*>, vaList: VaList): Float {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callFloatMethodV(vm, dvmObject, this, vaList)
    }

    fun callDoubleMethodV(dvmObject: DvmObject<*>, vaList: VaList): Double {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).callDoubleMethodV(vm, dvmObject, this, vaList)
    }

    fun toReflectedMethod(): DvmObject<*> {
        val vm = dvmClass.vm
        return checkJni(vm, dvmClass).toReflectedMethod(vm, dvmClass, this)
    }

    private var shortyCache: kotlin.Array<Shorty>? = null

    fun decodeArgsShorty(): kotlin.Array<Shorty> {
        if (shortyCache != null) {
            return shortyCache!!
        }

        val chars = args.toCharArray()
        val list: MutableList<Shorty> = ArrayList(chars.size)
        var arrayDimensions = 0
        var isType = false
        var shorty: Shorty? = null
        val binaryName = StringBuilder()
        var i = 1
        while (i < chars.size) {
            val c = chars[i]
            if (c == ')') {
                break
            }

            if (isType) {
                if (c == ';') {
                    isType = false
                    shorty!!.setBinaryName(binaryName.toString())
                    binaryName.delete(0, binaryName.length)
                } else {
                    binaryName.append(c)
                }
                i++
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
                    throw IllegalStateException("i=" + i + ", char=" + chars[i] + ", args=" + args)
                }
            }

            if (type == '0') {
                i++
                continue
            }

            shorty = Shorty(arrayDimensions, type)
            list.add(shorty)
            arrayDimensions = 0
            i++
        }
        shortyCache = list.toTypedArray()
        return shortyCache!!
    }

    @JvmField
    var member: Member? = null

    open fun setMember(member: Member) {
        (member as AccessibleObject).isAccessible = true
        if (!Modifier.isStatic(member.modifiers) && isStatic) {
            throw IllegalStateException(toString())
        }
        if (member.declaringClass.name == dvmClass.getName()) {
            this.member = member
        }
    }

    override fun toString(): String {
        return getSignature()
    }
}
