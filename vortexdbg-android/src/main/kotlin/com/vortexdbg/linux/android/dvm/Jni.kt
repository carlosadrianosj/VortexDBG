package com.vortexdbg.linux.android.dvm

interface Jni {

    fun callStaticFloatMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Float
    fun callStaticFloatMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Float

    fun callStaticDoubleMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Double
    fun callStaticDoubleMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Double

    fun callStaticVoidMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg)
    fun callStaticVoidMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg)

    fun callStaticVoidMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList)
    fun callStaticVoidMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList)

    fun callStaticBooleanMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Boolean
    fun callStaticBooleanMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Boolean

    fun callStaticBooleanMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Boolean
    fun callStaticBooleanMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): Boolean

    fun callStaticIntMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Int
    fun callStaticIntMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Int

    fun callStaticIntMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Int
    fun callStaticIntMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): Int

    fun callStaticLongMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Long
    fun callStaticLongMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Long

    fun callStaticLongMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Long
    fun callStaticLongMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): Long

    fun callStaticObjectMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*>
    fun callStaticObjectMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): DvmObject<*>

    fun callStaticObjectMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*>
    fun callStaticObjectMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): DvmObject<*>

    fun newObject(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*>
    fun newObject(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): DvmObject<*>

    fun newObjectV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*>
    fun newObjectV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): DvmObject<*>

    fun allocObject(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*>

    fun callVoidMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg)
    fun callVoidMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg)

    fun callVoidMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList)
    fun callVoidMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList)

    fun callBooleanMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Boolean
    fun callBooleanMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Boolean

    fun callBooleanMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Boolean
    fun callBooleanMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Boolean

    fun callIntMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Int
    fun callIntMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Int

    fun callDoubleMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Double
    fun callDoubleMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Double

    fun callByteMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Byte
    fun callByteMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Byte

    fun callShortMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Short
    fun callShortMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Short

    fun callIntMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Int
    fun callIntMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Int

    fun callObjectMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*>
    fun callObjectMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): DvmObject<*>

    fun callLongMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Long
    fun callLongMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Long

    fun callLongMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Long
    fun callLongMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Long

    fun callFloatMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Float
    fun callFloatMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Float

    fun callDoubleMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Double
    fun callDoubleMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Double

    fun callObjectMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*>
    fun callObjectMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): DvmObject<*>

    fun getStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Boolean
    fun getStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, signature: String): Boolean

    fun getStaticByteField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Byte
    fun getStaticByteField(vm: BaseVM, dvmClass: DvmClass, signature: String): Byte

    fun getStaticIntField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Int
    fun getStaticIntField(vm: BaseVM, dvmClass: DvmClass, signature: String): Int

    fun getStaticDoubleField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Double
    fun getStaticDoubleField(vm: BaseVM, dvmClass: DvmClass, signature: String): Double

    fun getStaticObjectField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): DvmObject<*>
    fun getStaticObjectField(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*>

    fun getBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Boolean
    fun getBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Boolean

    fun getByteField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Byte
    fun getByteField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Byte

    fun getIntField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Int
    fun getIntField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Int

    fun getLongField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Long
    fun getLongField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Long

    fun getFloatField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Float
    fun getFloatField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Float

    fun getDoubleField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Double
    fun getDoubleField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Double

    fun getObjectField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): DvmObject<*>
    fun getObjectField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): DvmObject<*>

    fun setBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Boolean)
    fun setBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Boolean)

    fun setIntField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Int)
    fun setIntField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Int)

    fun setFloatField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Float)
    fun setFloatField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Float)

    fun setDoubleField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Double)
    fun setDoubleField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Double)

    fun setLongField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Long)
    fun setLongField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Long)

    fun setObjectField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: DvmObject<*>)
    fun setObjectField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: DvmObject<*>)

    fun setStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Boolean)
    fun setStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Boolean)

    fun setStaticIntField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Int)
    fun setStaticIntField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Int)

    fun setStaticObjectField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: DvmObject<*>)
    fun setStaticObjectField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: DvmObject<*>)

    fun setStaticLongField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Long)
    fun setStaticLongField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Long)

    fun setStaticFloatField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Float)
    fun setStaticFloatField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Float)

    fun setStaticDoubleField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Double)
    fun setStaticDoubleField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Double)

    fun getStaticLongField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Long
    fun getStaticLongField(vm: BaseVM, dvmClass: DvmClass, signature: String): Long

    fun toReflectedMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod): DvmObject<*>
    fun toReflectedMethod(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*>

    fun acceptMethod(dvmClass: DvmClass, signature: String, isStatic: Boolean): Boolean

    fun acceptField(dvmClass: DvmClass, signature: String, isStatic: Boolean): Boolean

    fun callCharMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Char
    fun callCharMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Char
}
