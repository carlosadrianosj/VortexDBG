package com.vortexdbg.linux.android.dvm

abstract class JniFunction protected constructor(private val fallbackJni: Jni?) : Jni {

    override fun callStaticFloatMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Float {
        return callStaticFloatMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticFloatMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Float {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callStaticFloatMethod(vm, dvmClass, signature, varArg)
        }
    }

    override fun callStaticDoubleMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Double {
        return callStaticDoubleMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticDoubleMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Double {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callStaticDoubleMethod(vm, dvmClass, signature, varArg)
        }
    }

    override fun callStaticVoidMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg) {
        callStaticVoidMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticVoidMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.callStaticVoidMethod(vm, dvmClass, signature, varArg)
        }
    }

    override fun callStaticVoidMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList) {
        callStaticVoidMethodV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun callStaticVoidMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.callStaticVoidMethodV(vm, dvmClass, signature, vaList)
        }
    }

    override fun callStaticBooleanMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Boolean {
        return callStaticBooleanMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticBooleanMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Boolean {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callStaticBooleanMethod(vm, dvmClass, signature, varArg)
        }
    }

    override fun callStaticBooleanMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Boolean {
        return callStaticBooleanMethodV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun callStaticBooleanMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): Boolean {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callStaticBooleanMethodV(vm, dvmClass, signature, vaList)
        }
    }

    override fun callStaticIntMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Int {
        return callStaticIntMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticIntMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Int {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callStaticIntMethod(vm, dvmClass, signature, varArg)
        }
    }

    override fun callStaticIntMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Int {
        return callStaticIntMethodV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun callStaticIntMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): Int {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callStaticIntMethodV(vm, dvmClass, signature, vaList)
        }
    }

    override fun callStaticLongMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Long {
        return callStaticLongMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticLongMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Long {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callStaticLongMethod(vm, dvmClass, signature, varArg)
        }
    }

    override fun callStaticLongMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Long {
        return callStaticLongMethodV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun callStaticLongMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): Long {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callStaticLongMethodV(vm, dvmClass, signature, vaList)
        }
    }

    override fun callStaticObjectMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
        return callStaticObjectMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticObjectMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): DvmObject<*> {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callStaticObjectMethod(vm, dvmClass, signature, varArg)
        }
    }

    override fun callStaticObjectMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*> {
        return callStaticObjectMethodV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun callStaticObjectMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): DvmObject<*> {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callStaticObjectMethodV(vm, dvmClass, signature, vaList)
        }
    }

    override fun newObject(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
        return newObject(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun newObject(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): DvmObject<*> {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.newObject(vm, dvmClass, signature, varArg)
        }
    }

    override fun newObjectV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*> {
        return newObjectV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun newObjectV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): DvmObject<*> {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.newObjectV(vm, dvmClass, signature, vaList)
        }
    }

    override fun allocObject(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*> {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.allocObject(vm, dvmClass, signature)
        }
    }

    override fun callVoidMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg) {
        callVoidMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callVoidMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.callVoidMethod(vm, dvmObject, signature, varArg)
        }
    }

    override fun callVoidMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList) {
        callVoidMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callVoidMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.callVoidMethodV(vm, dvmObject, signature, vaList)
        }
    }

    override fun callBooleanMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Boolean {
        return callBooleanMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callBooleanMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Boolean {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callBooleanMethod(vm, dvmObject, signature, varArg)
        }
    }

    override fun callBooleanMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Boolean {
        return callBooleanMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callBooleanMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Boolean {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callBooleanMethodV(vm, dvmObject, signature, vaList)
        }
    }

    override fun callCharMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Char {
        return callCharMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callCharMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Char {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callCharMethodV(vm, dvmObject, signature, vaList)
        }
    }

    override fun callIntMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Int {
        return callIntMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callIntMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Int {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callIntMethod(vm, dvmObject, signature, varArg)
        }
    }

    override fun callDoubleMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Double {
        return callDoubleMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callDoubleMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Double {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callDoubleMethod(vm, dvmObject, signature, varArg)
        }
    }

    override fun callByteMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Byte {
        return callByteMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callByteMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Byte {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callByteMethodV(vm, dvmObject, signature, vaList)
        }
    }

    override fun callShortMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Short {
        return callShortMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callShortMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Short {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callShortMethodV(vm, dvmObject, signature, vaList)
        }
    }

    override fun callIntMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Int {
        return callIntMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callIntMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Int {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callIntMethodV(vm, dvmObject, signature, vaList)
        }
    }

    override fun callObjectMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
        return callObjectMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callObjectMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): DvmObject<*> {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callObjectMethod(vm, dvmObject, signature, varArg)
        }
    }

    override fun callLongMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Long {
        return callLongMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callLongMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Long {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callLongMethod(vm, dvmObject, signature, varArg)
        }
    }

    override fun callLongMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Long {
        return callLongMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callLongMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Long {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callLongMethodV(vm, dvmObject, signature, vaList)
        }
    }

    override fun callFloatMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Float {
        return callFloatMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callFloatMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Float {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callFloatMethodV(vm, dvmObject, signature, vaList)
        }
    }

    override fun callObjectMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*> {
        return callObjectMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callObjectMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): DvmObject<*> {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.callObjectMethodV(vm, dvmObject, signature, vaList)
        }
    }

    override fun getStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Boolean {
        return getStaticBooleanField(vm, dvmClass, dvmField.getSignature())
    }

    override fun getStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, signature: String): Boolean {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.getStaticBooleanField(vm, dvmClass, signature)
        }
    }

    override fun getStaticByteField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Byte {
        return getStaticByteField(vm, dvmClass, dvmField.getSignature())
    }

    override fun getStaticByteField(vm: BaseVM, dvmClass: DvmClass, signature: String): Byte {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.getStaticByteField(vm, dvmClass, signature)
        }
    }

    override fun getStaticIntField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Int {
        return getStaticIntField(vm, dvmClass, dvmField.getSignature())
    }

    override fun getStaticIntField(vm: BaseVM, dvmClass: DvmClass, signature: String): Int {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.getStaticIntField(vm, dvmClass, signature)
        }
    }

    override fun getStaticObjectField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): DvmObject<*> {
        return getStaticObjectField(vm, dvmClass, dvmField.getSignature())
    }

    override fun getStaticObjectField(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*> {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.getStaticObjectField(vm, dvmClass, signature)
        }
    }

    override fun getBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Boolean {
        return getBooleanField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Boolean {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.getBooleanField(vm, dvmObject, signature)
        }
    }

    override fun getByteField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Byte {
        return getByteField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getByteField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Byte {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.getByteField(vm, dvmObject, signature)
        }
    }

    override fun getIntField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Int {
        return getIntField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getIntField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Int {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.getIntField(vm, dvmObject, signature)
        }
    }

    override fun getLongField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Long {
        return getLongField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getLongField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Long {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.getLongField(vm, dvmObject, signature)
        }
    }

    override fun getFloatField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Float {
        return getFloatField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getFloatField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Float {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.getFloatField(vm, dvmObject, signature)
        }
    }

    override fun getObjectField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): DvmObject<*> {
        return getObjectField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getObjectField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): DvmObject<*> {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.getObjectField(vm, dvmObject, signature)
        }
    }

    override fun setBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Boolean) {
        setBooleanField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Boolean) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setBooleanField(vm, dvmObject, signature, value)
        }
    }

    override fun setIntField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Int) {
        setIntField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setIntField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Int) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setIntField(vm, dvmObject, signature, value)
        }
    }

    override fun setFloatField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Float) {
        setFloatField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setFloatField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Float) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setFloatField(vm, dvmObject, signature, value)
        }
    }

    override fun setDoubleField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Double) {
        setDoubleField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setDoubleField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Double) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setDoubleField(vm, dvmObject, signature, value)
        }
    }

    override fun setLongField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Long) {
        setLongField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setLongField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Long) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setLongField(vm, dvmObject, signature, value)
        }
    }

    override fun setObjectField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: DvmObject<*>) {
        setObjectField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setObjectField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: DvmObject<*>) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setObjectField(vm, dvmObject, signature, value)
        }
    }

    override fun setStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Boolean) {
        setStaticBooleanField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Boolean) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setStaticBooleanField(vm, dvmClass, signature, value)
        }
    }

    override fun setStaticIntField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Int) {
        setStaticIntField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticIntField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Int) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setStaticIntField(vm, dvmClass, signature, value)
        }
    }

    override fun setStaticObjectField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: DvmObject<*>) {
        setStaticObjectField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticObjectField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: DvmObject<*>) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setStaticObjectField(vm, dvmClass, signature, value)
        }
    }

    override fun setStaticLongField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Long) {
        setStaticLongField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticLongField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Long) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setStaticLongField(vm, dvmClass, signature, value)
        }
    }

    override fun setStaticFloatField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Float) {
        setStaticFloatField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticFloatField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Float) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setStaticFloatField(vm, dvmClass, signature, value)
        }
    }

    override fun setStaticDoubleField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Double) {
        setStaticDoubleField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticDoubleField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Double) {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            fallbackJni.setStaticDoubleField(vm, dvmClass, signature, value)
        }
    }

    override fun getStaticLongField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Long {
        return getStaticLongField(vm, dvmClass, dvmField.getSignature())
    }

    override fun getStaticLongField(vm: BaseVM, dvmClass: DvmClass, signature: String): Long {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.getStaticLongField(vm, dvmClass, signature)
        }
    }

    override fun toReflectedMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod): DvmObject<*> {
        return toReflectedMethod(vm, dvmClass, dvmMethod.getSignature())
    }

    override fun toReflectedMethod(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*> {
        if (fallbackJni == null) {
            throw UnsupportedOperationException(signature)
        } else {
            return fallbackJni.toReflectedMethod(vm, dvmClass, signature)
        }
    }

    override fun acceptMethod(dvmClass: DvmClass, signature: String, isStatic: Boolean): Boolean {
        return true
    }

    override fun acceptField(dvmClass: DvmClass, signature: String, isStatic: Boolean): Boolean {
        return true
    }
}
