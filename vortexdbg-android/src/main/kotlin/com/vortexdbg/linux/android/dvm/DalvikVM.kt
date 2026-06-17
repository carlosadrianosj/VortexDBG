package com.vortexdbg.linux.android.dvm

import com.vortexdbg.AndroidEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.arm.ArmHook
import com.vortexdbg.arm.ArmSvc
import com.vortexdbg.arm.HookStatus
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.context.EditableArm32RegisterContext
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.linux.android.dvm.apk.Apk
import com.vortexdbg.linux.android.dvm.array.ArrayObject
import com.vortexdbg.linux.android.dvm.array.ByteArray
import com.vortexdbg.linux.android.dvm.array.DoubleArray
import com.vortexdbg.linux.android.dvm.array.FloatArray
import com.vortexdbg.linux.android.dvm.array.IntArray
import com.vortexdbg.linux.android.dvm.array.PrimitiveArray
import com.vortexdbg.linux.android.dvm.array.ShortArray
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Objects

class DalvikVM(emulator: AndroidEmulator, apkFile: File?) : BaseVM(emulator, apkFile), VM {

    private val _JavaVM: VortexdbgPointer
    private val _JNIEnv: VortexdbgPointer

    init {
        val svcMemory: SvcMemory = emulator.getSvcMemory()
        _JavaVM = svcMemory.allocate(emulator.getPointerSize(), "_JavaVM")

        val _GetVersion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                return VM.JNI_VERSION_1_6.toLong()
            }
        })

        val _DefineClass = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _FindClass = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val env = context.getPointerArg(0)
                val className = context.getPointerArg(1)!!
                val name = className.getString(0L)

                val notFound = notFoundClassSet.contains(name)
                if (verbose) {
                    if (notFound) {
                        System.out.printf("JNIEnv->FindNoClass(%s) was called from %s%n", name, context.getLRPointer())
                    } else {
                        System.out.printf("JNIEnv->FindClass(%s) was called from %s%n", name, context.getLRPointer())
                    }
                }

                if (notFound) {
                    throwable = resolveClass("java/lang/NoClassDefFoundError").newObject(name)
                    return 0L
                }

                val dvmClass = resolveClass(name)
                val hash = dvmClass.hashCode().toLong() and 0xffffffffL
                if (log.isDebugEnabled()) {
                    log.debug("FindClass env={}, className={}, hash=0x{}", env, name, java.lang.Long.toHexString(hash))
                }
                return hash
            }
        })

        val _FromReflectedMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _FromReflectedField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ToReflectedMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val dvmClass = classMap.get(clazz.toIntPeer())
                var dvmMethod: DvmMethod? = null
                if (dvmClass != null) {
                    dvmMethod = dvmClass.getStaticMethod(jmethodID.toIntPeer())
                    if (dvmMethod == null) {
                        dvmMethod = dvmClass.getMethod(jmethodID.toIntPeer())
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("ToReflectedMethod clazz={}, jmethodID={}, lr={}", dvmClass, jmethodID, context.getLRPointer())
                }
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    if (verbose) {
                        System.out.printf("JNIEnv->ToReflectedMethod(%s, %s, %s) was called from %s%n", dvmClass!!.getClassName(), dvmMethod.methodName, if (dvmMethod.isStatic) "is static" else "not static", context.getLRPointer())
                    }

                    return addLocalObject(dvmMethod.toReflectedMethod()).toLong()
                }
            }
        })
        
        val _GetSuperclass = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val dvmClass = classMap.get(clazz.toIntPeer())!!
                if (verbose) {
                    System.out.printf("JNIEnv->GetSuperClass(%s) was called from %s%n", dvmClass, context.getLRPointer())
                }
                if (dvmClass.getClassName().equals("java/lang/Object")) {
                    log.debug("JNIEnv->GetSuperClass was called, class = {} According to Java Native Interface Specification, If clazz specifies the class Object, returns NULL.", dvmClass.getClassName())
                    throw BackendException()
                }
                val superClass = dvmClass.getSuperclass()
                if (superClass == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("JNIEnv->GetSuperClass was called, class = {}, superClass get failed.", dvmClass.getClassName())
                    }
                    throw BackendException()
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("JNIEnv->GetSuperClass was called, class = {}, superClass = {}", dvmClass.getClassName(), superClass.getClassName())
                    }
                    return superClass.hashCode().toLong()
                }
            }
        })

        val _IsAssignableFrom = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ToReflectedField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _Throw = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                log.warn("Throw dvmObject={}, class={}", dvmObject, if (dvmObject != null) dvmObject.getObjectType() else null)
                throwable = dvmObject
                return 0L
            }
        })

        val _ThrowNew = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ExceptionOccurred = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val exception = if (throwable == null) VM.JNI_NULL.toLong() else (throwable.hashCode().toLong() and 0xffffffffL)
                if (log.isDebugEnabled()) {
                    log.debug("ExceptionOccurred: 0x{}", java.lang.Long.toHexString(exception))
                }
                return exception
            }
        })

        val _ExceptionDescribe = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ExceptionClear = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                if (log.isDebugEnabled()) {
                    log.debug("ExceptionClear")
                }
                throwable = null
                return 0L
            }
        })

        val _FatalError = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _PushLocalFrame = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val capacity = context.getIntArg(1)
                if (log.isDebugEnabled()) {
                    log.debug("PushLocalFrame capacity={}", capacity)
                }
                return VM.JNI_OK.toLong()
            }
        })

        val _PopLocalFrame = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val jresult = context.getPointerArg(1)
                if (log.isDebugEnabled()) {
                    log.debug("PopLocalFrame jresult={}", jresult)
                }
                return (if (jresult == null) 0 else jresult.toIntPeer()).toLong()
            }
        })

        val _NewGlobalRef = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)
                if (`object` == null) {
                    return 0L
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("NewGlobalRef `object`={}, dvmObject={}", `object`, dvmObject)
                }
                if (verbose) {
                    System.out.printf("JNIEnv->NewGlobalRef(%s) was called from %s%n", dvmObject, context.getLRPointer())
                }
                return addGlobalObject(dvmObject).toLong()
            }
        })

        val _DeleteGlobalRef = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)
                if (log.isDebugEnabled()) {
                    log.debug("DeleteGlobalRef `object`={}", `object`)
                }
                val ref = if (`object` == null) null else globalObjectMap.get(`object`.toIntPeer())
                if (ref != null) {
                    ref.refCount--
                    if (ref.refCount <= 0) {
                        globalObjectMap.remove(`object`!!.toIntPeer())
                        ref.obj.onDeleteRef()
                    }
                }
                if (verbose) {
                    System.out.printf("JNIEnv->DeleteGlobalRef(%s) was called from %s%n", if (ref == null) `object` else ref, context.getLRPointer())
                }
                return 0L
            }
        })

        val _DeleteLocalRef = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)
                if (log.isDebugEnabled()) {
                    log.debug("DeleteLocalRef `object`={}", `object`)
                }
                return 0L
            }
        })

        val _IsSameObject = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val ref1 = context.getPointerArg(1)!!
                val ref2 = context.getPointerArg(2)
                if (log.isDebugEnabled()) {
                    log.debug("IsSameObject ref1={}, ref2={}, LR={}", ref1, ref2, context.getLRPointer())
                }
                return (if (ref1 == ref2 || ref1.equals(ref2)) VM.JNI_TRUE else VM.JNI_FALSE).toLong()
            }
        })

        val _NewLocalRef = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)
                if (`object` == null) {
                    return 0L
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("NewLocalRef `object`={}, dvmObject={}, class={}", `object`, dvmObject, if (dvmObject != null) dvmObject.getObjectType() else null)
                }
                if (verbose) {
                    System.out.printf("JNIEnv->NewLocalRef(%s) was called from %s%n", dvmObject, context.getLRPointer())
                }
                return `object`.toIntPeer().toLong()
            }
        })

        val _EnsureLocalCapacity = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val capacity = context.getIntArg(1)
                if (log.isDebugEnabled()) {
                    log.debug("EnsureLocalCapacity capacity={}", capacity)
                }
                return 0L
            }
        })

        val _AllocObject = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val dvmClass = classMap.get(clazz.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("AllocObject clazz={}, lr={}", dvmClass, context.getLRPointer())
                }
                if (dvmClass == null) {
                    throw BackendException()
                } else {
                    val obj = dvmClass.allocObject()
                    if (verbose) {
                        System.out.printf("JNIEnv->AllocObject(%s => %s) was called from %s%n", dvmClass.getClassName(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _NewObject = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("NewObject clazz={}, jmethodID={}, lr={}", dvmClass, jmethodID, context.getLRPointer())
                }
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val obj = dvmMethod.newObject(varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->NewObject(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _NewObjectV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("NewObjectV clazz={}, jmethodID={}, va_list={}, lr={}", dvmClass, jmethodID, va_list, context.getLRPointer())
                }
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val obj = dvmMethod.newObjectV(vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->NewObjectV(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _NewObjectA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("NewObjectA clazz={}, jmethodID={}, jvalue={}, lr={}", dvmClass, jmethodID, jvalue, context.getLRPointer())
                }
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = JValueList(this@DalvikVM, jvalue!!, dvmMethod)
                    val obj = dvmMethod.newObjectA(vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->NewObjectA(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _GetObjectClass = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)
                val dvmObject = if (`object` == null) null else getObject<DvmObject<*>>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("GetObjectClass `object`={}, dvmObject={}", `object`, dvmObject)
                }
                if (dvmObject == null) {
                    throw BackendException()
                } else {
                    val dvmClass = dvmObject.getObjectType()
                    return dvmClass.hashCode().toLong()
                }
            }
        })

        val _IsInstanceOf = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val clazz = context.getPointerArg(2)!!
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = classMap.get(clazz.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("IsInstanceOf `object`={}, clazz={}, dvmObject={}, dvmClass={}", `object`, clazz, dvmObject, dvmClass)
                }
                if (dvmObject == null || dvmClass == null) {
                    throw BackendException()
                }
                val flag = dvmObject.isInstanceOf(dvmClass)
                return (if (flag) VM.JNI_TRUE else VM.JNI_FALSE).toLong()
            }
        })

        val _GetMethodID = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val methodName = context.getPointerArg(2)!!
                val argsPointer = context.getPointerArg(3)!!
                val name = methodName.getString(0L)
                val args = argsPointer.getString(0L)
                if (log.isDebugEnabled()) {
                    log.debug("GetMethodID class={}, methodName={}, args={}, LR={}", clazz, name, args, context.getLRPointer())
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                if (dvmClass == null) {
                    throw BackendException()
                } else {
                    val hash = dvmClass.getMethodID(name, args)
                    if (verbose && hash != 0) {
                        System.out.printf("JNIEnv->GetMethodID(%s.%s%s) => 0x%x was called from %s%n", dvmClass.getClassName(), name, args, hash.toLong() and 0xffffffffL, context.getLRPointer())
                    }
                    return hash.toLong()
                }
            }
        })

        val _CallObjectMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallObjectMethod `object`={}, jmethodID={}", `object`, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val ret = dvmMethod.callObjectMethod(dvmObject, varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallObjectMethod(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    return addLocalObject(ret).toLong()
                }
            }
        })

        val _CallObjectMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallObjectMethodV `object`={}, jmethodID={}, va_list={}, lr={}", `object`, jmethodID, va_list, context.getLRPointer())
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException("dvmObject=" + dvmObject + ", dvmClass=" + dvmClass + ", jmethodID=" + jmethodID)
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val obj = dvmMethod.callObjectMethodV(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallObjectMethodV(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _CallObjectMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallObjectMethodA `object`={}, jmethodID={}, jvalue={}, lr={}", `object`, jmethodID, jvalue, context.getLRPointer())
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException("dvmObject=" + dvmObject + ", dvmClass=" + dvmClass + ", jmethodID=" + jmethodID)
                } else {
                    val vaList = JValueList(this@DalvikVM, jvalue!!, dvmMethod)
                    val obj = dvmMethod.callObjectMethodA(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallObjectMethodA(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _CallBooleanMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallBooleanMethod `object`={}, jmethodID={}", `object`, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val ret = dvmMethod.callBooleanMethod(dvmObject, varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallBooleanMethod(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    return (if (ret) VM.JNI_TRUE else VM.JNI_FALSE).toLong()
                }
            }
        })

        val _CallBooleanMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallBooleanMethodV `object`={}, jmethodID={}, va_list={}", `object`, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val ret = dvmMethod.callBooleanMethodV(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallBooleanMethodV(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return (if (ret) VM.JNI_TRUE else VM.JNI_FALSE).toLong()
                }
            }
        })

        val _CallBooleanMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallBooleanMethodA `object`={}, jmethodID={}, jvalue={}", `object`, jmethodID, jvalue)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = JValueList(this@DalvikVM, jvalue!!, dvmMethod)
                    val ret = dvmMethod.callBooleanMethodA(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallBooleanMethodA(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return (if (ret) VM.JNI_TRUE else VM.JNI_FALSE).toLong()
                }
            }
        })

        val _CallByteMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallByteMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallByteMethodV `object`={}, jmethodID={}, va_list={}", `object`, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val ret = dvmMethod.callByteMethodV(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallByteMethodV(%s, %s(%s) => 0x%x) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallByteMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallCharMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallCharMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallCharMethodV `object`={}, jmethodID={}, va_list={}", `object`, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val ret = dvmMethod.callCharMethodV(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallCharMethodV(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.code.toLong()
                }
            }
        })

        val _CallCharMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallShortMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallShortMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallShortMethodV `object`={}, jmethodID={}, va_list={}", `object`, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val ret = dvmMethod.callShortMethodV(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallShortMethodV(%s, %s(%s) => 0x%x) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallShortMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallIntMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallIntMethod `object`={}, jmethodID={}", `object`, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val ret = dvmMethod.callIntMethod(dvmObject, varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallIntMethod(%s, %s(%s) => 0x%x) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallIntMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallIntMethodV `object`={}, jmethodID={}, va_list={}", `object`, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val ret = dvmMethod.callIntMethodV(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallIntMethodV(%s, %s(%s) => 0x%x) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallIntMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallIntMethodA `object`={}, jmethodID={}, jvalue={}", `object`, jmethodID, jvalue)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = JValueList(this@DalvikVM, jvalue!!, dvmMethod)
                    val ret = dvmMethod.callIntMethodA(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallIntMethodA(%s, %s(%s) => 0x%x) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallLongMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<EditableArm32RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallLongMethod `object`={}, jmethodID={}", `object`, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val ret = dvmMethod.callLongMethod(dvmObject, varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallLongMethod(%s, %s(%s) => 0x%xL) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    context.setR1((ret shr 32).toInt())
                    return (ret and 0xffffffffL)
                }
            }
        })

        val _CallLongMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<EditableArm32RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallLongMethodV `object`={}, jmethodID={}, va_list={}", `object`, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val ret = dvmMethod.callLongMethodV(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallLongMethodV(%s, %s(%s) => 0x%xL) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    context.setR1((ret shr 32).toInt())
                    return (ret and 0xffffffffL)
                }
            }
        })

        val _CallLongMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallFloatMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallFloatMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallFloatMethodV `object`={}, jmethodID={}, va_list={}", `object`, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val ret = dvmMethod.callFloatMethodV(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallFloatMethodV(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(4)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putFloat(ret)
                    buffer.flip()
                    return (buffer.getInt().toLong() and 0xffffffffL)
                }
            }
        })

        val _CallFloatMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallDoubleMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallDoubleMethod `object`={}, jmethodID={}", `object`, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val ret = dvmMethod.callDoubleMethod(dvmObject, varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallDoubleMethod(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(4)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putFloat(ret.toFloat())
                    buffer.flip()
                    return (buffer.getInt().toLong() and 0xffffffffL)
                }
            }
        })

        val _CallDoubleMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallDoubleMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallVoidMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallVoidMethod `object`={}, jmethodID={}", `object`, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    dvmMethod.callVoidMethod(dvmObject, varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallVoidMethod(%s, %s(%s)) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), context.getLRPointer())
                    }
                    return 0L
                }
            }
        })

        val _CallVoidMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallVoidMethodV `object`={}, jmethodID={}, va_list={}", `object`, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    dvmMethod.callVoidMethodV(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallVoidMethodV(%s, %s(%s)) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return 0L
                }
            }
        })

        val _CallVoidMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallVoidMethodA `object`={}, jmethodID={}, jvalue={}", `object`, jmethodID, jvalue)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = JValueList(this@DalvikVM, jvalue!!, dvmMethod)
                    dvmMethod.callVoidMethodA(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallVoidMethodA(%s, %s(%s)) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return 0L
                }
            }
        })

        val _CallNonvirtualObjectMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualObjectMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualObjectMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualBooleanMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualBooleanMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualBooleanMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val clazz = context.getPointerArg(2)!!
                val jmethodID = context.getPointerArg(3)!!
                val jvalue = context.getPointerArg(4)
                if (log.isDebugEnabled()) {
                    log.debug("CallNonvirtualBooleanMethodA `object`={}, clazz={}, jmethodID={}, jvalue={}", `object`, clazz, jmethodID, jvalue)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = JValueList(this@DalvikVM, jvalue!!, dvmMethod)
                    if (dvmMethod.isConstructor()) {
                        throw IllegalStateException()
                    }
                    val ret = dvmMethod.callBooleanMethodA(dvmObject, vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallNonvirtualBooleanMethodA(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return (if (ret) VM.JNI_TRUE else VM.JNI_FALSE).toLong()
                }
            }
        })

        val _CallNonvirtualByteMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualByteMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualByteMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualCharMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualCharMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualCharMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualShortMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualShortMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualShortMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualIntMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualIntMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualIntMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualLongMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualLongMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualLongMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualFloatMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualFloatMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualFloatMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualDoubleMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualDoubleMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualDoubleMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualVoidMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualVoidMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val clazz = context.getPointerArg(2)!!
                val jmethodID = context.getPointerArg(3)!!
                val va_list = context.getPointerArg(4)
                if (log.isDebugEnabled()) {
                    log.debug("CallNonvirtualVoidMethodV `object`={}, clazz={}, jmethodID={}, va_list={}", `object`, clazz, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    if (dvmMethod.isConstructor()) {
                        val obj = dvmMethod.newObjectV(vaList)
                        Objects.requireNonNull(dvmObject).setValue(obj.getValue())
                    } else {
                        dvmMethod.callVoidMethodV(dvmObject, vaList)
                    }
                    if (verbose) {
                        System.out.printf("JNIEnv->CallNonvirtualVoidMethodV(%s, %s, %s(%s)) was called from %s%n", dvmObject, dvmClass!!.getClassName(), dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return 0L
                }
            }
        })

        val _CallNonVirtualVoidMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val clazz = context.getPointerArg(2)!!
                val jmethodID = context.getPointerArg(3)!!
                val jvalue = context.getPointerArg(4)
                if (log.isDebugEnabled()) {
                    log.debug("CallNonVirtualVoidMethodA `object`={}, clazz={}, jmethodID={}, jvalue={}", `object`, clazz, jmethodID, jvalue)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = JValueList(this@DalvikVM, jvalue!!, dvmMethod)
                    if (dvmMethod.isConstructor()) {
                        val obj = dvmMethod.newObjectV(vaList)
                        Objects.requireNonNull(dvmObject).setValue(obj.getValue())
                    } else {
                        dvmMethod.callVoidMethodA(dvmObject, vaList)
                    }
                    if (verbose) {
                        System.out.printf("JNIEnv->CallNonVirtualVoidMethodA(%s, %s, %s(%s)) was called from %s%n", dvmObject, dvmClass!!.getClassName(), dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return 0L
                }
            }
        })

        val _GetFieldID = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val fieldName = context.getPointerArg(2)!!
                val argsPointer = context.getPointerArg(3)!!
                val name = fieldName.getString(0L)
                val args = argsPointer.getString(0L)
                if (log.isDebugEnabled()) {
                    log.debug("GetFieldID class={}, fieldName={}, args={}", clazz, name, args)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                if (dvmClass == null) {
                    throw BackendException()
                } else {
                    val hash = dvmClass.getFieldID(name, args)
                    if (verbose && hash != 0) {
                        System.out.printf("JNIEnv->GetFieldID(%s.%s %s) => 0x%x was called from %s%n", dvmClass.getClassName(), name, args, hash.toLong() and 0xffffffffL, context.getLRPointer())
                    }
                    return hash.toLong()
                }
            }
        })

        val _GetObjectField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("GetObjectField `object`={}, jfieldID={}", `object`, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val obj = dvmField.getObjectField(dvmObject)
                    if (verbose) {
                        System.out.printf("JNIEnv->GetObjectField(%s, %s %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, dvmField.fieldType, obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _GetBooleanField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("GetBooleanField `object`={}, jfieldID={}", `object`, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getBooleanField(dvmObject)
                    if (verbose) {
                        System.out.printf("JNIEnv->GetBooleanField(%s, %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, ret == VM.JNI_TRUE, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _GetByteField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetCharField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetShortField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetIntField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("GetIntField `object`={}, jfieldID={}", `object`, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getIntField(dvmObject)
                    if (verbose) {
                        System.out.printf("JNIEnv->GetIntField(%s, %s => 0x%x) was called from %s%n", dvmObject, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _GetLongField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<EditableArm32RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("GetLongField `object`={}, jfieldID={}", `object`, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getLongField(dvmObject)
                    if (verbose) {
                        System.out.printf("JNIEnv->GetLongField(%s, %s => 0x%x) was called from %s%n", dvmObject, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    context.setR1((ret shr 32).toInt())
                    return ret.toLong()
                }
            }
        })

        val _GetFloatField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("GetFloatField `object`={}, jfieldID={}", `object`, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getFloatField(dvmObject)
                    if (verbose) {
                        System.out.printf("JNIEnv->GetFloatField(%s, %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(4)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putFloat(ret)
                    buffer.flip()
                    return (buffer.getInt().toLong() and 0xffffffffL)
                }
            }
        })

        val _GetDoubleField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetObjectField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("SetObjectField `object`={}, jfieldID={}, value={}", `object`, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val obj = if (value == null) null else getObject<DvmObject<*>>(value.toIntPeer())
                    dvmField.setObjectField(dvmObject, obj)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetObjectField(%s, %s %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, dvmField.fieldType, obj, context.getLRPointer())
                    }
                }
                return 0L
            }
        })

        val _SetBooleanField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getIntArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("SetBooleanField `object`={}, jfieldID={}, value={}", `object`, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val flag = BaseVM.valueOf(value)
                    dvmField.setBooleanField(dvmObject, flag)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetBooleanField(%s, %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, flag, context.getLRPointer())
                    }
                }
                return 0L
            }
        })

        val _SetByteField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetCharField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetShortField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetIntField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getIntArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("SetIntField `object`={}, jfieldID={}, value={}", `object`, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    dvmField.setIntField(dvmObject, value)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetIntField(%s, %s => 0x%x) was called from %s%n", dvmObject, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0L
            }
        })
        
        val _SetLongField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val sp = context.getStackPointer()
                val value = sp.getLong(0L)
                if (log.isDebugEnabled()) {
                    log.debug("SetLongField `object`={}, jfieldID={}, value={}", `object`, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    dvmField.setLongField(dvmObject, value)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetLongField(%s, %s => 0x%x) was called from %s%n", dvmObject, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0L
            }
        })

        val _SetFloatField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val buffer = ByteBuffer.allocate(4)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(context.getIntArg(3))
                buffer.flip()
                val value = buffer.getFloat()
                if (log.isDebugEnabled()) {
                    log.debug("SetFloatField `object`={}, jfieldID={}, value={}", `object`, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    dvmField.setFloatField(dvmObject, value)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetFloatField(%s, %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0L
            }
        })
        
        val _SetDoubleField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val sp = context.getStackPointer()
                val value = sp.getDouble(0L)
                if (log.isDebugEnabled()) {
                    log.debug("SetDoubleField `object`={}, jfieldID={}, value={}", `object`, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(`object`.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    dvmField.setDoubleField(dvmObject, value)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetDoubleField(%s, %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0L
            }
        })

        val _GetStaticMethodID = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val methodName = context.getPointerArg(2)!!
                val argsPointer = context.getPointerArg(3)!!
                val name = methodName.getString(0L)
                val args = argsPointer.getString(0L)
                if (log.isDebugEnabled()) {
                    log.debug("GetStaticMethodID class={}, methodName={}, args={}, LR={}", clazz, name, args, context.getLRPointer())
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                if (dvmClass == null) {
                    throw BackendException()
                } else {
                    val hash = dvmClass.getStaticMethodID(name, args)
                    if (verbose && hash != 0) {
                        System.out.printf("JNIEnv->GetStaticMethodID(%s.%s%s) => 0x%x was called from %s%n", dvmClass.getClassName(), name, args, hash.toLong() and 0xffffffffL, context.getLRPointer())
                    }
                    return hash.toLong()
                }
            }
        })

        val _CallStaticObjectMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticObjectMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val obj = dvmMethod.callStaticObjectMethod(varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticObjectMethod(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _CallStaticBooleanMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticBooleanMethodA clazz={}, jmethodID={}, jvalue={}", clazz, jmethodID, jvalue)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = JValueList(this@DalvikVM, jvalue!!, dvmMethod)
                    val ret = dvmMethod.callStaticBooleanMethodV(vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticBooleanMethodA(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return (if (ret) VM.JNI_TRUE else VM.JNI_FALSE).toLong()
                }
            }
        })

        val _CallStaticObjectMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticObjectMethodV clazz={}, jmethodID={}, va_list={}", clazz, jmethodID, va_list)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val obj = dvmMethod.callStaticObjectMethodV(vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticObjectMethodV(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _CallStaticObjectMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticObjectMethodA clazz={}, jmethodID={}, jvalue={}", clazz, jmethodID, jvalue)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = JValueList(this@DalvikVM, jvalue!!, dvmMethod)
                    val obj = dvmMethod.callStaticObjectMethodA(vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticObjectMethodA(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _CallStaticBooleanMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticBooleanMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val ret = dvmMethod.CallStaticBooleanMethod(varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticBooleanMethod(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    return (if (ret) VM.JNI_TRUE else VM.JNI_FALSE).toLong()
                }
            }
        })

        val _CallStaticBooleanMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticBooleanMethodV clazz={}, jmethodID={}, va_list={}", clazz, jmethodID, va_list)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val ret = dvmMethod.callStaticBooleanMethodV(vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticBooleanMethodV(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return (if (ret) VM.JNI_TRUE else VM.JNI_FALSE).toLong()
                }
            }
        })

        val _CallStaticByteMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticByteMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticByteMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticCharMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticCharMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticCharMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticShortMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticShortMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticShortMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticIntMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticIntMethodV clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val ret = dvmMethod.callStaticIntMethod(varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticIntMethod(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallStaticIntMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticIntMethodV clazz={}, jmethodID={}, va_list={}", clazz, jmethodID, va_list)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val ret = dvmMethod.callStaticIntMethodV(vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticIntMethodV(%s, %s(%s) => 0x%x) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallStaticIntMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticLongMethod = svcMemory.registerSvc(object : ArmHook() {
            override fun hook(emulator: Emulator<*>): HookStatus {
                val context = emulator.getContext<EditableArm32RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticLongMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val value = dvmMethod.callStaticLongMethod(varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticLongMethod(%s, %s(%s)) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), context.getLRPointer())
                    }
                    context.setR1((value shr 32).toInt())
                    return HookStatus.LR(emulator, value and 0xffffffffL)
                }
            }
        })

        val _CallStaticLongMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<EditableArm32RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticLongMethodV clazz={}, jmethodID={}, va_list={}, lr={}", clazz, jmethodID, va_list, context.getLRPointer())
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    val ret = dvmMethod.callStaticLongMethodV(vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticLongMethodV(%s, %s(%s) => 0x%x) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    context.setR1((ret shr 32).toInt())
                    return (ret and 0xffffffffL)
                }
            }
        })

        val _CallStaticLongMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticFloatMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticFloatMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val ret = dvmMethod.callStaticFloatMethod(varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticFloatMethod(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(4)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putFloat(ret)
                    buffer.flip()
                    return (buffer.getInt().toLong() and 0xffffffffL)
                }
            }
        })

        val _CallStaticFloatMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticFloatMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticDoubleMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<EditableArm32RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticDoubleMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    val ret = dvmMethod.callStaticDoubleMethod(varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticDoubleMethod(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(8)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putDouble(ret)
                    buffer.flip()
                    val i1 = buffer.getInt()
                    val i2 = buffer.getInt()
                    context.setR1(i2)
                    return i1.toLong()
                }
            }
        })

        val _CallStaticDoubleMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticDoubleMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticVoidMethod = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticVoidMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM, dvmMethod)
                    dvmMethod.callStaticVoidMethod(varArg)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticVoidMethod(%s, %s(%s)) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), context.getLRPointer())
                    }
                    return 0L
                }
            }
        })

        val _CallStaticVoidMethodV = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticVoidMethodV clazz={}, jmethodID={}, va_list={}", clazz, jmethodID, va_list)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = VaList32(emulator, this@DalvikVM, va_list!!, dvmMethod)
                    dvmMethod.callStaticVoidMethodV(vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticVoidMethodV(%s, %s(%s)) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return 0L
                }
            }
        })

        val _CallStaticVoidMethodA = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("CallStaticVoidMethodA clazz={}, jmethodID={}, jvalue={}", clazz, jmethodID, jvalue)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList = JValueList(this@DalvikVM, jvalue!!, dvmMethod)
                    dvmMethod.callStaticVoidMethodA(vaList)
                    if (verbose) {
                        System.out.printf("JNIEnv->CallStaticVoidMethodA(%s, %s(%s)) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return 0L
                }
            }
        })

        val _GetStaticFieldID = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val fieldName = context.getPointerArg(2)!!
                val argsPointer = context.getPointerArg(3)!!
                val name = fieldName.getString(0L)
                val args = argsPointer.getString(0L)
                if (log.isDebugEnabled()) {
                    log.debug("GetStaticFieldID class={}, fieldName={}, args={}", clazz, name, args)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                if (dvmClass == null) {
                    throw BackendException()
                } else {
                    val hash = dvmClass.getStaticFieldID(name, args)
                    if (verbose && hash != 0) {
                        System.out.printf("JNIEnv->GetStaticFieldID(%s.%s%s) => 0x%x was called from %s%n", dvmClass.getClassName(), name, args, hash.toLong() and 0xffffffffL, context.getLRPointer())
                    }
                    return hash.toLong()
                }
            }
        })

        val _GetStaticObjectField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("GetStaticObjectField clazz={}, jfieldID={}", clazz, jfieldID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val obj = dvmField.getStaticObjectField()
                    if (verbose) {
                        System.out.printf("JNIEnv->GetStaticObjectField(%s, %s %s => %s) was called from %s%n", dvmClass, dvmField.fieldName, dvmField.fieldType, obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _GetStaticBooleanField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("GetStaticBooleanField clazz={}, jfieldID={}", clazz, jfieldID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getStaticBooleanField()
                    if (verbose) {
                        System.out.printf("JNIEnv->GetStaticBooleanField(%s, %s => %s) was called from %s%n", dvmClass, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    return (if (ret) VM.JNI_TRUE else VM.JNI_FALSE).toLong()
                }
            }
        })

        val _GetStaticByteField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("GetStaticByteField clazz={}, jfieldID={}", clazz, jfieldID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getStaticByteField()
                    if (verbose) {
                        System.out.printf("JNIEnv->GetStaticByteField(%s, %s => %s) was called from %s%n", dvmClass, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _GetStaticCharField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetStaticShortField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetStaticIntField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("GetStaticIntField clazz={}, jfieldID={}", clazz, jfieldID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getStaticIntField()
                    if (verbose) {
                        System.out.printf("JNIEnv->GetStaticIntField(%s, %s => 0x%x) was called from %s%n", dvmClass, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _GetStaticLongField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<EditableArm32RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled()) {
                    log.debug("GetStaticLongField clazz={}, jfieldID={}", clazz, jfieldID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getStaticLongField()
                    if (verbose) {
                        System.out.printf("JNIEnv->GetStaticLongField(%s, %s => 0x%x) was called from %s%n", dvmClass, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    context.setR1((ret shr 32).toInt())
                    return ret.toLong()
                }
            }
        })

        val _GetStaticFloatField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetStaticDoubleField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetStaticObjectField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("SetStaticObjectField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmObject = if (value == null) null else getObject<DvmObject<*>>(value.toIntPeer())
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=" + dvmClass)
                } else {
                    dvmField.setStaticObjectField(dvmObject)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetStaticObjectField(%s, %s, %s) was called from %s%n", dvmClass, dvmField.fieldName, dvmObject, context.getLRPointer())
                    }
                }
                return 0L
            }
        })

        val _SetStaticBooleanField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getIntArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("SetStaticBooleanField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=" + dvmClass)
                } else {
                    val flag = BaseVM.valueOf(value)
                    dvmField.setStaticBooleanField(flag)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetStaticBooleanField(%s, %s, %s) was called from %s%n", dvmClass, dvmField.fieldName, flag, context.getLRPointer())
                    }
                }
                return 0L
            }
        })

        val _SetStaticByteField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetStaticCharField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetStaticShortField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetStaticIntField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getIntArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("SetStaticIntField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=" + dvmClass)
                } else {
                    dvmField.setStaticIntField(value)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetStaticIntField(%s, %s, 0x%x) was called from %s%n", dvmClass, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0L
            }
        })

        val _SetStaticLongField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val sp = context.getStackPointer()
                val value = sp.getLong(0L)
                if (log.isDebugEnabled()) {
                    log.debug("SetStaticLongField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=" + dvmClass)
                } else {
                    dvmField.setStaticLongField(value)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetStaticLongField(%s, %s, 0x%x) was called from %s%n", dvmClass, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0L
            }
        })

        val _SetStaticFloatField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val buffer = ByteBuffer.allocate(4)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(context.getIntArg(3))
                buffer.flip()
                val value = buffer.getFloat()
                if (log.isDebugEnabled()) {
                    log.debug("SetStaticFloatField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=" + dvmClass)
                } else {
                    dvmField.setStaticFloatField(value)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetStaticFloatField(%s, %s, %s) was called from %s%n", dvmClass, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0L
            }
        })

        val _SetStaticDoubleField = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val sp = context.getStackPointer()
                val value = sp.getDouble(0L)
                if (log.isDebugEnabled()) {
                    log.debug("SetStaticDoubleField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=" + dvmClass)
                } else {
                    dvmField.setStaticDoubleField(value)
                    if (verbose) {
                        System.out.printf("JNIEnv->SetStaticDoubleField(%s, %s, %s) was called from %s%n", dvmClass, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0L
            }
        })

        val _GetStringUTFLength = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val string = getObject<DvmObject<*>>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("GetStringUTFLength string={}, lr={}", string, context.getLRPointer())
                }
                val value = Objects.requireNonNull(string).getValue() as String
                if (verbose) {
                    System.out.printf("JNIEnv->GetStringUTFLength(%s) was called from %s%n", string, context.getLRPointer())
                }
                val data = value.toByteArray(StandardCharsets.UTF_8)
                return data.size.toLong()
            }
        })

        val _GetStringUTFChars = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val string = getObject<StringObject>(`object`.toIntPeer())
                if (isCopy != null) {
                    isCopy.setInt(0L, VM.JNI_TRUE)
                }
                val value = Objects.requireNonNull(string).getValue() as String
                if (verbose) {
                    System.out.printf("JNIEnv->GetStringUtfChars(%s) was called from %s%n", string, context.getLRPointer())
                }
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                if (log.isDebugEnabled()) {
                    log.debug("GetStringUTFChars string={}, isCopy={}, value={}, lr={}", string, isCopy, value, context.getLRPointer())
                }
                val data = Arrays.copyOf(bytes, bytes.size + 1)
                val pointer = string.allocateMemoryBlock(emulator, data.size)
                pointer.write(0L, data, 0, data.size)
                return pointer.toIntPeer().toLong()
            }
        })

        val _ReleaseStringUTFChars = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val string = getObject<StringObject>(`object`.toIntPeer())
                if (verbose) {
                    System.out.printf("JNIEnv->ReleaseStringUTFChars(%s) was called from %s%n", string, context.getLRPointer())
                }
                if (log.isDebugEnabled()) {
                    log.debug("ReleaseStringUTFChars string={}, pointer={}, lr={}", string, pointer, context.getLRPointer())
                }
                Objects.requireNonNull(string).freeMemoryBlock(pointer)
                return 0L
            }
        })

        val _GetArrayLength = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val pointer = context.getPointerArg(1)!!
                val array = Objects.requireNonNull(getObject<DvmObject<*>>(pointer.toIntPeer())) as Array<*>
                if (log.isDebugEnabled()) {
                    log.debug("GetArrayLength array={}, lr={}", array, context.getLRPointer())
                }
                if (verbose) {
                    System.out.printf("JNIEnv->GetArrayLength(%s => %s) was called from %s%n", array, array.length(), context.getLRPointer())
                }
                return array.length().toLong()
            }
        })

        val _NewObjectArray = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                val elementClass = context.getPointerArg(2)!!
                val initialElement = context.getPointerArg(3)
                if (log.isDebugEnabled()) {
                    log.debug("NewObjectArray size={}, elementClass={}, initialElement={}", size, elementClass, initialElement)
                }
                val dvmClass = classMap.get(elementClass.toIntPeer())
                if (dvmClass == null) {
                    throw BackendException("elementClass=" + elementClass)
                }

                val obj = if (size == 0) null else if (initialElement == null) null else getObject<DvmObject<*>>(initialElement.toIntPeer())
                val array = arrayOfNulls<DvmObject<*>>(size)
                Arrays.fill(array, obj)

                return addLocalObject(ArrayObject(*array)).toLong()
            }
        })

        val _GetObjectArrayElement = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val index = context.getIntArg(2)
                val array = getObject<ArrayObject>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("GetObjectArrayElement array={}, index={}", array, index)
                }
                val obj = Objects.requireNonNull(array).getValue()[index]
                if (verbose) {
                    System.out.printf("JNIEnv->GetObjectArrayElement(%s, %d) => %s was called from %s%n", array, index, obj, context.getLRPointer())
                }
                return addLocalObject(obj).toLong()
            }
        })

        val _SetObjectArrayElement = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val index = context.getIntArg(2)
                val element = context.getPointerArg(3)
                val array = getObject<ArrayObject>(`object`.toIntPeer())
                val obj = if (element == null) null else getObject<DvmObject<*>>(element.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("setObjectArrayElement array={}, index={}, obj={}", array, index, obj)
                }
                val objs = Objects.requireNonNull(array).getValue()
                objs[index] = obj
                return 0L
            }
        })

        val _NewLongArray = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _NewFloatArray = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                if (log.isDebugEnabled()) {
                    log.debug("NewFloatArray size={}", size)
                }
                if (verbose) {
                    System.out.printf("JNIEnv->NewFloatArray(%d) was called from %s%n", size, context.getLRPointer())
                }
                return addLocalObject(FloatArray(this@DalvikVM, kotlin.FloatArray(size))).toLong()
            }
        })

        val _GetLongArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetFloatArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val array = getObject<FloatArray>(`object`.toIntPeer())
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).toIntPeer().toLong()
            }
        })

        val _GetDoubleArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _NewBooleanArray = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })
        
        val _NewByteArray = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                if (log.isDebugEnabled()) {
                    log.debug("NewByteArray size={}, LR={}, PC={}", size, context.getLRPointer(), context.getPCPointer())
                }
                if (verbose) {
                    System.out.printf("JNIEnv->NewByteArray(%d) was called from %s%n", size, context.getLRPointer())
                }
                return addLocalObject(ByteArray(this@DalvikVM, kotlin.ByteArray(size))).toLong()
            }
        })

        val _NewCharArray = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _NewShortArray = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _NewIntArray = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                if (log.isDebugEnabled()) {
                    log.debug("NewIntArray size={}", size)
                }
                if (verbose) {
                    System.out.printf("JNIEnv->NewIntArray(%d) was called from %s%n", size, context.getLRPointer())
                }
                return addLocalObject(IntArray(this@DalvikVM, kotlin.IntArray(size))).toLong()
            }
        })
        
        val _NewDoubleArray = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                if (log.isDebugEnabled()) {
                    log.debug("_NewDoubleArray size={}", size)
                }
                if (verbose) {
                    System.out.printf("JNIEnv->NewDoubleArray(%d) was called from %s%n", size, context.getLRPointer())
                }
                return addLocalObject(DoubleArray(this@DalvikVM, kotlin.DoubleArray(size))).toLong()
            }
        })

        val _GetBooleanArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetByteArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val array = getObject<ByteArray>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    Inspector.inspect(array.getValue(), "GetByteArrayElements array=" + array + ", isCopy=" + isCopy)
                }
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).toIntPeer().toLong()
            }
        })

        val _GetCharArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetShortArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetIntArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val array = getObject<IntArray>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("GetIntArrayElements array={}, isCopy={}", array, isCopy)
                }
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).toIntPeer().toLong()
            }
        })

        val _NewString = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val unicodeChars = context.getPointerArg(1)
                val len = context.getIntArg(2)
                if (unicodeChars == null) {
                    if (len == 0) {
                        return VM.JNI_NULL.toLong()
                    }
                    throw IllegalStateException("unicodeChars is null")
                }
                val buffer = ByteBuffer.wrap(unicodeChars.getByteArray(0L, len * 2))
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val builder = StringBuilder(len)
                for (i in 0 until len) {
                    builder.append(buffer.getChar())
                }
                val string = builder.toString()
                if (log.isDebugEnabled()) {
                    log.debug("NewString unicodeChars={}, len={}, string={}", unicodeChars, len, string)
                }
                if (verbose) {
                    System.out.printf("JNIEnv->NewString(\"%s\") was called from %s%n", string, context.getLRPointer())
                }
                return addLocalObject(StringObject(this@DalvikVM, string)).toLong()
            }
        })

        val _GetStringLength = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val string = getObject<DvmObject<*>>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("GetStringLength string={}, lr={}", string, context.getLRPointer())
                }
                val value = Objects.requireNonNull(string).getValue() as String
                return value.length.toLong()
            }
        })

        val _GetStringChars = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val string = getObject<StringObject>(`object`.toIntPeer())
                if (isCopy != null) {
                    isCopy.setInt(0L, VM.JNI_TRUE)
                }
                val value = Objects.requireNonNull(string).getValue() as String
                val bytes = ByteArray(value.length * 2)
                val buffer = ByteBuffer.wrap(bytes)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (c in value.toCharArray()) {
                    buffer.putChar(c)
                }
                if (log.isDebugEnabled()) {
                    log.debug("GetStringChars string={}, isCopy={}, value={}, lr={}", string, isCopy, value, context.getLRPointer())
                }
                if (verbose) {
                    System.out.printf("JNIEnv->GetStringUTFChars(\"%s\") was called from %s%n", value, context.getLRPointer())
                }
                val data = Arrays.copyOf(bytes, bytes.size + 1)
                val pointer = string.allocateMemoryBlock(emulator, data.size)
                pointer.write(0L, data, 0, data.size)
                return pointer.toIntPeer().toLong()
            }
        })

        val _ReleaseStringChars = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val string = getObject<StringObject>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("ReleaseStringChars string={}, pointer={}, lr={}", string, pointer, context.getLRPointer())
                }
                Objects.requireNonNull(string).freeMemoryBlock(pointer)
                return 0L
            }
        })

        val _NewStringUTF = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val bytes = context.getPointerArg(1)
                if (bytes == null) {
                    return VM.JNI_NULL.toLong()
                }

                val string = bytes.getString(0L)
                if (log.isDebugEnabled()) {
                    log.debug("NewStringUTF bytes={}, string={}", bytes, string)
                }
                if (verbose) {
                    System.out.printf("JNIEnv->NewStringUTF(\"%s\") was called from %s%n", string, context.getLRPointer())
                }
                return addLocalObject(StringObject(this@DalvikVM, string)).toLong()
            }
        })

        val _ReleaseBooleanArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ReleaseByteArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val mode = context.getIntArg(3)
                val array = getObject<ByteArray>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("ReleaseByteArrayElements array={}, pointer={}, mode={}", array, pointer, mode)
                }
                Objects.requireNonNull(array)._ReleaseArrayCritical(pointer!!, mode)
                return 0L
            }
        })

        val _ReleaseCharArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ReleaseShortArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ReleaseIntArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val mode = context.getIntArg(3)
                val array = getObject<IntArray>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("ReleaseIntArrayElements array={}, pointer={}, mode={}", array, pointer, mode)
                }
                Objects.requireNonNull(array)._ReleaseArrayCritical(pointer!!, mode)
                return 0L
            }
        })

        val _ReleaseLongArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ReleaseFloatArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val mode = context.getIntArg(3)
                val array = getObject<FloatArray>(`object`.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("ReleaseFloatArrayElements array={}, pointer={}, mode={}", array, pointer, mode)
                }
                Objects.requireNonNull(array)._ReleaseArrayCritical(pointer!!, mode)
                return 0L
            }
        })

        val _ReleaseDoubleArrayElements = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetBooleanArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetByteArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<ByteArray>(`object`.toIntPeer())
                if (verbose) {
                    System.out.printf("JNIEnv->GetByteArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(Objects.requireNonNull(array).getValue(), start, start + length)
                if (log.isDebugEnabled()) {
                    Inspector.inspect(data, "GetByteArrayRegion array=" + array + ", start=" + start + ", length=" + length + ", buf=" + buf)
                }
                buf.write(0L, data, 0, data.size)
                return 0L
            }
        })

        val _GetCharArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetShortArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<ShortArray>(`object`.toIntPeer())
                if (verbose) {
                    System.out.printf("JNIEnv->GetShortArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(Objects.requireNonNull(array).getValue(), start, start + length)
                if (log.isDebugEnabled()) {
                    log.debug("GetShortArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                buf.write(0L, data, 0, data.size)
                return 0L
            }
        })

        val _GetIntArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetLongArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetFloatArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetDoubleArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<DoubleArray>(`object`.toIntPeer())
                if (verbose) {
                    System.out.printf("JNIEnv->GetDoubleArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(Objects.requireNonNull(array).getValue(), start, start + length)
                if (log.isDebugEnabled()) {
                    log.debug("GetDoubleArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                buf.write(0L, data, 0, data.size)
                return 0L
            }
        })

        val _SetBooleanArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetByteArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<ByteArray>(`object`.toIntPeer())
                if (verbose) {
                    System.out.printf("JNIEnv->SetByteArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = buf.getByteArray(0L, length)
                if (log.isDebugEnabled()) {
                    if (data.size > 1024) {
                        Inspector.inspect(Arrays.copyOf(data, 1024), "SetByteArrayRegion array=" + array + ", start=" + start + ", length=" + length + ", buf=" + buf)
                    } else {
                        Inspector.inspect(data, "SetByteArrayRegion array=" + array + ", start=" + start + ", length=" + length + ", buf=" + buf)
                    }
                }
                Objects.requireNonNull(array).setData(start, data)
                return 0L
            }
        })

        val _SetCharArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetShortArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })
        
        val _SetIntArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<IntArray>(`object`.toIntPeer())
                val data = buf.getIntArray(0L, length)
                if (log.isDebugEnabled()) {
                    log.debug("SetIntArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                Objects.requireNonNull(array).setData(start, data)
                return 0L
            }
        })

        val _SetLongArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetFloatArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<FloatArray>(`object`.toIntPeer())
                val data = buf.getFloatArray(0L, length)
                if (log.isDebugEnabled()) {
                    log.debug("SetFloatArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                Objects.requireNonNull(array).setData(start, data)
                return 0L
            }
        })
        
        val _SetDoubleArrayRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<DoubleArray>(`object`.toIntPeer())
                val data = buf.getDoubleArray(0L, length)
                if (log.isDebugEnabled()) {
                    log.debug("SetDoubleArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                Objects.requireNonNull(array).setData(start, data)
                return 0L
            }
        })

        val _RegisterNatives = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val methods = context.getPointerArg(2)!!
                val nMethods = context.getIntArg(3)
                val dvmClass = classMap.get(clazz.toIntPeer())!!
                if (log.isDebugEnabled()) {
                    log.debug("RegisterNatives dvmClass={}, methods={}, nMethods={}", dvmClass, methods, nMethods)
                }
                if (verbose) {
                    System.out.printf("JNIEnv->RegisterNatives(%s, %s, %d) was called from %s%n", dvmClass!!.getClassName(), methods, nMethods, context.getLRPointer())
                }
                for (i in 0 until nMethods) {
                    val method = methods.share(i.toLong() * 0xcL)
                    val name = method.getPointer(0L)
                    val signature = method.getPointer(4L)
                    val fnPtr = method.getPointer(8L)
                    val methodName = name.getString(0L)
                    val signatureValue = signature.getString(0L)
                    if (log.isDebugEnabled()) {
                        log.debug("RegisterNatives dvmClass={}, name={}, signature={}, fnPtr={}", dvmClass, methodName, signatureValue, fnPtr)
                    }
                    dvmClass!!.nativesMap.put(methodName + signatureValue, fnPtr as VortexdbgPointer)

                    if (verbose) {
                        System.out.printf("RegisterNative(%s, %s%s, %s)%n", dvmClass!!.getClassName(), methodName, signatureValue, fnPtr)
                    }
                }
                return VM.JNI_OK.toLong()
            }
        })

        val _UnregisterNatives = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _MonitorEnter = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val env = context.getPointerArg(0)
                val obj = getObject<DvmObject<*>>(context.getPointerArg(1)!!.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("MonitorEnter env={}, obj={}", env, obj)
                }
                return 0L
            }
        })

        val _MonitorExit = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val env = context.getPointerArg(0)
                val obj = getObject<DvmObject<*>>(context.getPointerArg(1)!!.toIntPeer())
                if (log.isDebugEnabled()) {
                    log.debug("MonitorExit env={}, obj={}", env, obj)
                }
                return 0L
            }
        })

        val _GetJavaVM = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val vm = context.getPointerArg(1)!!
                if (log.isDebugEnabled()) {
                    log.debug("GetJavaVM vm={}", vm)
                }
                vm.setPointer(0L, _JavaVM)
                return VM.JNI_OK.toLong()
            }
        })

        val _GetStringRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!

                val string = getObject<StringObject>(`object`.toIntPeer())
                val value = Objects.requireNonNull(string).getValue() as String
                if (verbose) {
                    System.out.printf("JNIEnv->GetStringRegion(%s) was called from %s%n", string, context.getLRPointer())
                }
                val bytes = ByteArray(value.length * 2)
                val buffer = ByteBuffer.wrap(bytes)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (c in value.toCharArray()) {
                    buffer.putChar(c)
                }
                if (log.isDebugEnabled()) {
                    log.debug("GetStringRegion string={}, value={}, start={}, length={}, buf{}, lr={}", string, value, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(bytes, start, start+length+1)
                buf.write(0L, data, 0, data.size)
                return VM.JNI_OK.toLong()
            }
        })

        val _GetStringUTFRegion = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!

                val string = getObject<StringObject>(`object`.toIntPeer())
                val value = Objects.requireNonNull(string).getValue() as String
                if (verbose) {
                    System.out.printf("JNIEnv->GetStringUTFRegion(%s) was called from %s%n", string, context.getLRPointer())
                }
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                if (log.isDebugEnabled()) {
                    log.debug("GetStringUTFRegion string={}, value={}, start={}, length={}, buf{}, lr={}", string, value, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(bytes, start, start+length+1)
                buf.write(0L, data, 0, data.size)
                return VM.JNI_OK.toLong()
            }
        })

        val _GetPrimitiveArrayCritical = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val array = getObject<DvmObject<*>>(`object`.toIntPeer()) as PrimitiveArray<*>
                if (log.isDebugEnabled()) {
                    log.debug("GetPrimitiveArrayCritical array={}, isCopy={}", array, isCopy)
                }
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).toIntPeer().toLong()
            }
        })

        val _ReleasePrimitiveArrayCritical = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val mode = context.getIntArg(3)
                val array = getObject<DvmObject<*>>(`object`.toIntPeer()) as PrimitiveArray<*>
                if (log.isDebugEnabled()) {
                    log.debug("ReleasePrimitiveArrayCritical array={}, pointer={}, mode={}", array, pointer, mode)
                }
                Objects.requireNonNull(array)._ReleaseArrayCritical(pointer!!, mode)
                return 0L
            }
        })

        val _GetStringCritical = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ReleaseStringCritical = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _NewWeakGlobalRef = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)
                if (`object` == null) {
                    return 0L
                }
                val dvmObject = Objects.requireNonNull(getObject<DvmObject<*>>(`object`.toIntPeer()))
                if (log.isDebugEnabled()) {
                    log.debug("NewWeakGlobalRef `object`={}, dvmObject={}, class={}", `object`, dvmObject, dvmObject.javaClass)
                }
                return addObject(dvmObject, true, true).toLong()
            }
        })

        val _DeleteWeakGlobalRef = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ExceptionCheck = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                if (log.isDebugEnabled()) {
                    log.debug("ExceptionCheck throwable={}", throwable)
                }
                return (if (throwable == null) VM.JNI_FALSE else VM.JNI_TRUE).toLong()
            }
        })

        val _NewDirectByteBuffer = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetDirectBufferAddress = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetDirectBufferCapacity = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetObjectRefType = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val `object` = context.getPointerArg(1)
                if (`object` == null) {
                    return VM.JNIInvalidRefType.toLong()
                }
                val hash = `object`.toIntPeer()
                val dvmLocalObject = localObjectMap.get(`object`.toIntPeer())
                val dvmGlobalObject: BaseVM.ObjRef?
                if (globalObjectMap.containsKey(hash)) {
                    dvmGlobalObject = globalObjectMap.get(hash)
                } else {
                    dvmGlobalObject = weakGlobalObjectMap.getOrDefault(hash, null)
                }
                if (log.isDebugEnabled()) {
                    log.debug("GetObjectRefType `object`={}, dvmGlobalObject={}, dvmLocalObject={}, LR={}", `object`, dvmGlobalObject, dvmLocalObject, context.getLRPointer())
                }
                if (dvmGlobalObject != null) {
                    return (if (dvmGlobalObject.weak) VM.JNIWeakGlobalRefType else VM.JNIGlobalRefType).toLong()
                } else if(dvmLocalObject != null) {
                    return VM.JNILocalRefType.toLong()
                } else {
                    return VM.JNIInvalidRefType.toLong()
                }
            }
        })

        val _GetModule = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val last = 0x3a4
        val impl = svcMemory.allocate(last + 4, "JNIEnv.impl")
        var i = 0
        while (i <= last) {
            impl.setInt(i.toLong(), i)
            i += 4
        }
        impl.setPointer(0x10L, _GetVersion)
        impl.setPointer(0x14L, _DefineClass)
        impl.setPointer(0x18L, _FindClass)
        impl.setPointer(0x1cL, _FromReflectedMethod)
        impl.setPointer(0x20L, _FromReflectedField)
        impl.setPointer(0x24L, _ToReflectedMethod)
        impl.setPointer(0x28L, _GetSuperclass)
        impl.setPointer(0x2cL, _IsAssignableFrom)
        impl.setPointer(0x30L, _ToReflectedField)
        impl.setPointer(0x34L, _Throw)
        impl.setPointer(0x38L, _ThrowNew)
        impl.setPointer(0x3cL, _ExceptionOccurred)
        impl.setPointer(0x40L, _ExceptionDescribe)
        impl.setPointer(0x44L, _ExceptionClear)
        impl.setPointer(0x48L, _FatalError)
        impl.setPointer(0x4cL, _PushLocalFrame)
        impl.setPointer(0x50L, _PopLocalFrame)
        impl.setPointer(0x54L, _NewGlobalRef)
        impl.setPointer(0x58L, _DeleteGlobalRef)
        impl.setPointer(0x5cL, _DeleteLocalRef)
        impl.setPointer(0x60L, _IsSameObject)
        impl.setPointer(0x64L, _NewLocalRef)
        impl.setPointer(0x68L, _EnsureLocalCapacity)
        impl.setPointer(0x6cL, _AllocObject)
        impl.setPointer(0x70L, _NewObject)
        impl.setPointer(0x74L, _NewObjectV)
        impl.setPointer(0x78L, _NewObjectA)
        impl.setPointer(0x7cL, _GetObjectClass)
        impl.setPointer(0x80L, _IsInstanceOf)
        impl.setPointer(0x84L, _GetMethodID)
        impl.setPointer(0x88L, _CallObjectMethod)
        impl.setPointer(0x8cL, _CallObjectMethodV)
        impl.setPointer(0x90L, _CallObjectMethodA)
        impl.setPointer(0x94L, _CallBooleanMethod)
        impl.setPointer(0x98L, _CallBooleanMethodV)
        impl.setPointer(0x9cL, _CallBooleanMethodA)
        impl.setPointer(0xa0L, _CallByteMethod)
        impl.setPointer(0xa4L, _CallByteMethodV)
        impl.setPointer(0xa8L, _CallByteMethodA)
        impl.setPointer(0xacL, _CallCharMethod)
        impl.setPointer(0xb0L, _CallCharMethodV)
        impl.setPointer(0xb4L, _CallCharMethodA)
        impl.setPointer(0xb8L, _CallShortMethod)
        impl.setPointer(0xbcL, _CallShortMethodV)
        impl.setPointer(0xc0L, _CallShortMethodA)
        impl.setPointer(0xc4L, _CallIntMethod)
        impl.setPointer(0xc8L, _CallIntMethodV)
        impl.setPointer(0xccL, _CallIntMethodA)
        impl.setPointer(0xd0L, _CallLongMethod)
        impl.setPointer(0xd4L, _CallLongMethodV)
        impl.setPointer(0xd8L, _CallLongMethodA)
        impl.setPointer(0xdcL, _CallFloatMethod)
        impl.setPointer(0xe0L, _CallFloatMethodV)
        impl.setPointer(0xe4L, _CallFloatMethodA)
        impl.setPointer(0xe8L, _CallDoubleMethod)
        impl.setPointer(0xecL, _CallDoubleMethodV)
        impl.setPointer(0xf0L, _CallDoubleMethodA)
        impl.setPointer(0xf4L, _CallVoidMethod)
        impl.setPointer(0xf8L, _CallVoidMethodV)
        impl.setPointer(0xfcL, _CallVoidMethodA)
        impl.setPointer(0x100L, _CallNonvirtualObjectMethod)
        impl.setPointer(0x104L, _CallNonvirtualObjectMethodV)
        impl.setPointer(0x108L, _CallNonvirtualObjectMethodA)
        impl.setPointer(0x10cL, _CallNonvirtualBooleanMethod)
        impl.setPointer(0x110L, _CallNonvirtualBooleanMethodV)
        impl.setPointer(0x114L, _CallNonvirtualBooleanMethodA)
        impl.setPointer(0x118L, _CallNonvirtualByteMethod)
        impl.setPointer(0x11cL, _CallNonvirtualByteMethodV)
        impl.setPointer(0x120L, _CallNonvirtualByteMethodA)
        impl.setPointer(0x124L, _CallNonvirtualCharMethod)
        impl.setPointer(0x128L, _CallNonvirtualCharMethodV)
        impl.setPointer(0x12cL, _CallNonvirtualCharMethodA)
        impl.setPointer(0x130L, _CallNonvirtualShortMethod)
        impl.setPointer(0x134L, _CallNonvirtualShortMethodV)
        impl.setPointer(0x138L, _CallNonvirtualShortMethodA)
        impl.setPointer(0x13cL, _CallNonvirtualIntMethod)
        impl.setPointer(0x140L, _CallNonvirtualIntMethodV)
        impl.setPointer(0x144L, _CallNonvirtualIntMethodA)
        impl.setPointer(0x148L, _CallNonvirtualLongMethod)
        impl.setPointer(0x14cL, _CallNonvirtualLongMethodV)
        impl.setPointer(0x150L, _CallNonvirtualLongMethodA)
        impl.setPointer(0x154L, _CallNonvirtualFloatMethod)
        impl.setPointer(0x158L, _CallNonvirtualFloatMethodV)
        impl.setPointer(0x15cL, _CallNonvirtualFloatMethodA)
        impl.setPointer(0x160L, _CallNonvirtualDoubleMethod)
        impl.setPointer(0x164L, _CallNonvirtualDoubleMethodV)
        impl.setPointer(0x168L, _CallNonvirtualDoubleMethodA)
        impl.setPointer(0x16cL, _CallNonvirtualVoidMethod)
        impl.setPointer(0x170L, _CallNonvirtualVoidMethodV)
        impl.setPointer(0x174L, _CallNonVirtualVoidMethodA)
        impl.setPointer(0x178L, _GetFieldID)
        impl.setPointer(0x17cL, _GetObjectField)
        impl.setPointer(0x180L, _GetBooleanField)
        impl.setPointer(0x184L, _GetByteField)
        impl.setPointer(0x188L, _GetCharField)
        impl.setPointer(0x18cL, _GetShortField)
        impl.setPointer(0x190L, _GetIntField)
        impl.setPointer(0x194L, _GetLongField)
        impl.setPointer(0x198L, _GetFloatField)
        impl.setPointer(0x19cL, _GetDoubleField)
        impl.setPointer(0x1a0L, _SetObjectField)
        impl.setPointer(0x1a4L, _SetBooleanField)
        impl.setPointer(0x1a8L, _SetByteField)
        impl.setPointer(0x1acL, _SetCharField)
        impl.setPointer(0x1b0L, _SetShortField)
        impl.setPointer(0x1b4L, _SetIntField)
        impl.setPointer(0x1b8L, _SetLongField)
        impl.setPointer(0x1bcL, _SetFloatField)
        impl.setPointer(0x1c0L, _SetDoubleField)
        impl.setPointer(0x1c4L, _GetStaticMethodID)
        impl.setPointer(0x1c8L, _CallStaticObjectMethod)
        impl.setPointer(0x1ccL, _CallStaticObjectMethodV)
        impl.setPointer(0x1d0L, _CallStaticObjectMethodA)
        impl.setPointer(0x1d4L, _CallStaticBooleanMethod)
        impl.setPointer(0x1d8L, _CallStaticBooleanMethodV)
        impl.setPointer(0x1dcL, _CallStaticBooleanMethodA)
        impl.setPointer(0x1e0L, _CallStaticByteMethod)
        impl.setPointer(0x1e4L, _CallStaticByteMethodV)
        impl.setPointer(0x1e8L, _CallStaticByteMethodA)
        impl.setPointer(0x1ecL, _CallStaticCharMethod)
        impl.setPointer(0x1f0L, _CallStaticCharMethodV)
        impl.setPointer(0x1f4L, _CallStaticCharMethodA)
        impl.setPointer(0x1f8L, _CallStaticShortMethod)
        impl.setPointer(0x1fcL, _CallStaticShortMethodV)
        impl.setPointer(0x200L, _CallStaticShortMethodA)
        impl.setPointer(0x204L, _CallStaticIntMethod)
        impl.setPointer(0x208L, _CallStaticIntMethodV)
        impl.setPointer(0x20cL, _CallStaticIntMethodA)
        impl.setPointer(0x210L, _CallStaticLongMethod)
        impl.setPointer(0x214L, _CallStaticLongMethodV)
        impl.setPointer(0x218L, _CallStaticLongMethodA)
        impl.setPointer(0x21cL, _CallStaticFloatMethod)
        impl.setPointer(0x220L, _CallStaticFloatMethodV)
        impl.setPointer(0x224L, _CallStaticFloatMethodA)
        impl.setPointer(0x228L, _CallStaticDoubleMethod)
        impl.setPointer(0x22cL, _CallStaticDoubleMethodV)
        impl.setPointer(0x230L, _CallStaticDoubleMethodA)
        impl.setPointer(0x234L, _CallStaticVoidMethod)
        impl.setPointer(0x238L, _CallStaticVoidMethodV)
        impl.setPointer(0x23cL, _CallStaticVoidMethodA)
        impl.setPointer(0x240L, _GetStaticFieldID)
        impl.setPointer(0x244L, _GetStaticObjectField)
        impl.setPointer(0x248L, _GetStaticBooleanField)
        impl.setPointer(0x24cL, _GetStaticByteField)
        impl.setPointer(0x250L, _GetStaticCharField)
        impl.setPointer(0x254L, _GetStaticShortField)
        impl.setPointer(0x258L, _GetStaticIntField)
        impl.setPointer(0x25cL, _GetStaticLongField)
        impl.setPointer(0x260L, _GetStaticFloatField)
        impl.setPointer(0x264L, _GetStaticDoubleField)
        impl.setPointer(0x268L, _SetStaticObjectField)
        impl.setPointer(0x26cL, _SetStaticBooleanField)
        impl.setPointer(0x270L, _SetStaticByteField)
        impl.setPointer(0x274L, _SetStaticCharField)
        impl.setPointer(0x278L, _SetStaticShortField)
        impl.setPointer(0x27cL, _SetStaticIntField)
        impl.setPointer(0x280L, _SetStaticLongField)
        impl.setPointer(0x284L, _SetStaticFloatField)
        impl.setPointer(0x288L, _SetStaticDoubleField)
        impl.setPointer(0x28cL, _NewString)
        impl.setPointer(0x290L, _GetStringLength)
        impl.setPointer(0x294L, _GetStringChars)
        impl.setPointer(0x298L, _ReleaseStringChars)
        impl.setPointer(0x29cL, _NewStringUTF)
        impl.setPointer(0x2a0L, _GetStringUTFLength)
        impl.setPointer(0x2a4L, _GetStringUTFChars)
        impl.setPointer(0x2a8L, _ReleaseStringUTFChars)
        impl.setPointer(0x2acL, _GetArrayLength)
        impl.setPointer(0x2b0L, _NewObjectArray)
        impl.setPointer(0x2b4L, _GetObjectArrayElement)
        impl.setPointer(0x2b8L, _SetObjectArrayElement)
        impl.setPointer(0x2bcL, _NewBooleanArray)
        impl.setPointer(0x2c0L, _NewByteArray)
        impl.setPointer(0x2c4L, _NewCharArray)
        impl.setPointer(0x2c8L, _NewShortArray)
        impl.setPointer(0x2ccL, _NewIntArray)
        impl.setPointer(0x2d0L, _NewLongArray)
        impl.setPointer(0x2d4L, _NewFloatArray)
        impl.setPointer(0x2d8L, _NewDoubleArray)
        impl.setPointer(0x2dcL, _GetBooleanArrayElements)
        impl.setPointer(0x2e0L, _GetByteArrayElements)
        impl.setPointer(0x2e4L, _GetCharArrayElements)
        impl.setPointer(0x2e8L, _GetShortArrayElements)
        impl.setPointer(0x2ecL, _GetIntArrayElements)
        impl.setPointer(0x2f0L, _GetLongArrayElements)
        impl.setPointer(0x2f4L, _GetFloatArrayElements)
        impl.setPointer(0x2f8L, _GetDoubleArrayElements)
        impl.setPointer(0x2fcL, _ReleaseBooleanArrayElements)
        impl.setPointer(0x300L, _ReleaseByteArrayElements)
        impl.setPointer(0x304L, _ReleaseCharArrayElements)
        impl.setPointer(0x308L, _ReleaseShortArrayElements)
        impl.setPointer(0x30cL, _ReleaseIntArrayElements)
        impl.setPointer(0x310L, _ReleaseLongArrayElements)
        impl.setPointer(0x314L, _ReleaseFloatArrayElements)
        impl.setPointer(0x318L, _ReleaseDoubleArrayElements)
        impl.setPointer(0x31cL, _GetBooleanArrayRegion)
        impl.setPointer(0x320L, _GetByteArrayRegion)
        impl.setPointer(0x324L, _GetCharArrayRegion)
        impl.setPointer(0x328L, _GetShortArrayRegion)
        impl.setPointer(0x32cL, _GetIntArrayRegion)
        impl.setPointer(0x330L, _GetLongArrayRegion)
        impl.setPointer(0x334L, _GetFloatArrayRegion)
        impl.setPointer(0x338L, _GetDoubleArrayRegion)
        impl.setPointer(0x33cL, _SetBooleanArrayRegion)
        impl.setPointer(0x340L, _SetByteArrayRegion)
        impl.setPointer(0x344L, _SetCharArrayRegion)
        impl.setPointer(0x348L, _SetShortArrayRegion)
        impl.setPointer(0x34cL, _SetIntArrayRegion)
        impl.setPointer(0x350L, _SetLongArrayRegion)
        impl.setPointer(0x354L, _SetFloatArrayRegion)
        impl.setPointer(0x358L, _SetDoubleArrayRegion)
        impl.setPointer(0x35cL, _RegisterNatives)
        impl.setPointer(0x360L, _UnregisterNatives)
        impl.setPointer(0x364L, _MonitorEnter)
        impl.setPointer(0x368L, _MonitorExit)
        impl.setPointer(0x36cL, _GetJavaVM)
        impl.setPointer(0x370L, _GetStringRegion)
        impl.setPointer(0x374L, _GetStringUTFRegion)
        impl.setPointer(0x378L, _GetPrimitiveArrayCritical)
        impl.setPointer(0x37cL, _ReleasePrimitiveArrayCritical)
        impl.setPointer(0x380L, _GetStringCritical)
        impl.setPointer(0x384L, _ReleaseStringCritical)
        impl.setPointer(0x388L, _NewWeakGlobalRef)
        impl.setPointer(0x38cL, _DeleteWeakGlobalRef)
        impl.setPointer(0x390L, _ExceptionCheck)
        impl.setPointer(0x394L, _NewDirectByteBuffer)
        impl.setPointer(0x398L, _GetDirectBufferAddress)
        impl.setPointer(0x39cL, _GetDirectBufferCapacity)
        impl.setPointer(0x3a0L, _GetObjectRefType)
        impl.setPointer(last.toLong(), _GetModule)

        _JNIEnv = svcMemory.allocate(emulator.getPointerSize(), "_JNIEnv")
        _JNIEnv.setPointer(0L, impl)

        val _AttachCurrentThread = svcMemory.registerSvc(object : ArmSvc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val vm = context.getPointerArg(0)
                val env = context.getPointerArg(1)
                val args = context.getPointerArg(2) // JavaVMAttachArgs*
                if (log.isDebugEnabled()) {
                    log.debug("AttachCurrentThread vm={}, env={}, args={}", vm, env!!.getPointer(0L), args)
                }
                env!!.setPointer(0L, _JNIEnv)
                return VM.JNI_OK.toLong()
            }
        })

        val _GetEnv = svcMemory.registerSvc(object : ArmSvc("_GetEnv") {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val vm = context.getPointerArg(0)
                val env = context.getPointerArg(1)
                val version = context.getIntArg(2)
                if (log.isDebugEnabled()) {
                    log.debug("GetEnv vm={}, env={}, version=0x{}", vm, env!!.getPointer(0L), java.lang.Integer.toHexString(version))
                }
                env!!.setPointer(0L, _JNIEnv)
                return VM.JNI_OK.toLong()
            }
        })

        val _JNIInvokeInterface = svcMemory.allocate(emulator.getPointerSize() * 8, "_JNIInvokeInterface")
        var j = 0
        while (j < emulator.getPointerSize() * 8) {
            _JNIInvokeInterface.setInt(j.toLong(), j)
            j += emulator.getPointerSize()
        }
        _JNIInvokeInterface.setPointer((emulator.getPointerSize() * 4).toLong(), _AttachCurrentThread)
        _JNIInvokeInterface.setPointer((emulator.getPointerSize() * 6).toLong(), _GetEnv)

        _JavaVM.setPointer(0L, _JNIInvokeInterface)

        if (log.isDebugEnabled()) {
            log.debug("_JavaVM={}, _JNIInvokeInterface={}, _JNIEnv={}", _JavaVM, _JNIInvokeInterface, _JNIEnv)
        }
    }

    override fun getJavaVM(): Pointer {
        return _JavaVM
    }

    override fun getJNIEnv(): Pointer {
        return _JNIEnv
    }

    internal override fun loadLibraryData(apk: Apk, soName: String): kotlin.ByteArray? {
        var soData = apk.getFileData("lib/armeabi-v7a/$soName")
        if (soData != null) {
            if (log.isDebugEnabled()) {
                log.debug("resolve armeabi-v7a library: {}", soName)
            }
            return soData
        }
        soData = apk.getFileData("lib/armeabi/$soName")
        if (soData != null && log.isDebugEnabled()) {
            log.debug("resolve armeabi library: {}", soName)
        }
        return soData
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(DalvikVM::class.java)
    }
}
