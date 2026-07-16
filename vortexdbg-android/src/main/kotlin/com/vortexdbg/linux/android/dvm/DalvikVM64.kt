package com.vortexdbg.linux.android.dvm

import com.vortexdbg.AndroidEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.arm.Arm64Hook
import com.vortexdbg.arm.Arm64Svc
import com.vortexdbg.arm.HookStatus
import com.vortexdbg.arm.NestedRun
import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.arm.context.Arm64RegisterContext
import com.vortexdbg.arm.context.RegisterContext
import com.vortexdbg.linux.android.dvm.apk.Apk
import com.vortexdbg.linux.android.dvm.array.ArrayObject
import com.vortexdbg.linux.android.dvm.array.ByteArray
import com.vortexdbg.linux.android.dvm.array.CharArray
import com.vortexdbg.linux.android.dvm.array.DoubleArray
import com.vortexdbg.linux.android.dvm.array.FloatArray
import com.vortexdbg.linux.android.dvm.array.IntArray
import com.vortexdbg.linux.android.dvm.array.LongArray
import com.vortexdbg.linux.android.dvm.array.PrimitiveArray
import com.vortexdbg.linux.android.dvm.array.ShortArray
import com.vortexdbg.memory.SvcMemory
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.utils.Inspector
import com.sun.jna.Pointer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unicorn.Arm64Const

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Objects

class DalvikVM64(emulator: AndroidEmulator, apkFile: File?) : BaseVM(emulator, apkFile), VM {

    private val _JavaVM: VortexdbgPointer
    private val _JNIEnv: VortexdbgPointer

    init {
        val svcMemory: SvcMemory = emulator.getSvcMemory()
        _JavaVM = svcMemory.allocate(emulator.getPointerSize(), "_JavaVM")

        val _GetVersion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                return VM.JNI_VERSION_1_8.toLong()
            }
        })

        val _DefineClass: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _FindClass: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
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
                    return 0
                }

                val dvmClass = resolveClass(name)
                val hash = dvmClass.hashCode().toLong() and 0xffffffffL
                if (log.isDebugEnabled) {
                    log.debug("FindClass env={}, className={}, hash=0x{}", env, name, java.lang.Long.toHexString(hash))
                }
                return hash
            }
        })

        val _FromReflectedMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _FromReflectedField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ToReflectedMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
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
                if (log.isDebugEnabled) {
                    log.debug("ToReflectedMethod clazz={}, jmethodID={}, lr={}", dvmClass, jmethodID, context.getLRPointer())
                }
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->ToReflectedMethod(%s, \"%s\", %s) was called from %s%n", dvmClass!!.getClassName(), dvmMethod.methodName, if (dvmMethod.isStatic) "is static" else "not static", context.getLRPointer())
                    }

                    return addLocalObject(dvmMethod.toReflectedMethod()).toLong()
                }
            }
        })

        val _GetSuperclass: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val dvmClass = classMap.get(clazz.toIntPeer())
                if (verbose || verboseMethodOperation) {
                    System.out.printf("JNIEnv->GetSuperClass(%s) was called from %s%n", dvmClass, context.getLRPointer())
                }
                if (dvmClass!!.getClassName() == "java/lang/Object") {
                    log.debug("JNIEnv->GetSuperClass was called, class = {} According to Java Native Interface Specification, If clazz specifies the class Object, returns NULL.", dvmClass.getClassName())
                    throw BackendException()
                }
                val superClass = dvmClass.getSuperclass()
                if (superClass == null) {
                    if (log.isDebugEnabled) {
                        log.debug("JNIEnv->GetSuperClass was called, class = {}, superClass get failed.", dvmClass.getClassName())
                    }
                    throw BackendException()
                } else {
                    if (log.isDebugEnabled) {
                        log.debug("JNIEnv->GetSuperClass was called, class = {}, superClass = {}", dvmClass.getClassName(), superClass.getClassName())
                    }
                    return superClass.hashCode().toLong()
                }
            }
        })

        val _IsAssignableFrom: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ToReflectedField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _Throw: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                log.warn("Throw dvmObject={}, class={}", dvmObject, if (dvmObject != null) dvmObject.getObjectType() else null)
                throwable = dvmObject
                return 0
            }
        })

        val _ThrowNew: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val message = context.getPointerArg(2)
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmObject = dvmClass!!.newObject(message)
                log.debug("ThrowNew clazz={}, lr={}", dvmClass, context.getLRPointer())
                throwable = dvmObject
                return 0
            }
        })

        val _ExceptionOccurred: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val exception = if (throwable == null) VM.JNI_NULL.toLong() else (throwable!!.hashCode().toLong() and 0xffffffffL)
                if (log.isDebugEnabled) {
                    log.debug("ExceptionOccurred: 0x{}", java.lang.Long.toHexString(exception))
                }
                return exception
            }
        })

        val _ExceptionDescribe: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ExceptionClear: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                if (log.isDebugEnabled) {
                    log.debug("ExceptionClear")
                }
                throwable = null
                return 0
            }
        })

        val _FatalError: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _PushLocalFrame: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val capacity = context.getIntArg(1)
                if (log.isDebugEnabled) {
                    log.debug("PushLocalFrame capacity={}", capacity)
                }
                return VM.JNI_OK.toLong()
            }
        })

        val _PopLocalFrame: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val jresult = context.getPointerArg(1)
                if (log.isDebugEnabled) {
                    log.debug("PopLocalFrame jresult={}", jresult)
                }
                return if (jresult == null) 0 else jresult.toIntPeer().toLong()
            }
        })

        val _NewGlobalRef: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)
                if (obj == null) {
                    return 0
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("NewGlobalRef object={}, dvmObject={}", obj, dvmObject)
                }
                if (dvmObject == null) {
                    throw IllegalStateException("LR=" + context.getLRPointer())
                }
                if (verbose) {
                    System.out.printf("JNIEnv->NewGlobalRef(%s) was called from %s%n", dvmObject, context.getLRPointer())
                }
                return addGlobalObject(dvmObject).toLong()
            }
        })

        val _DeleteGlobalRef: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)
                if (log.isDebugEnabled) {
                    log.debug("DeleteGlobalRef object={}", obj)
                }
                val ref = if (obj == null) null else globalObjectMap.get(obj.toIntPeer())
                if (ref != null) {
                    ref.refCount--
                    if (ref.refCount <= 0) {
                        globalObjectMap.remove(obj!!.toIntPeer())
                        ref.obj.onDeleteRef()
                    }
                }
                if (verbose) {
                    System.out.printf("JNIEnv->DeleteGlobalRef(%s) was called from %s%n", if (ref == null) obj else ref, context.getLRPointer())
                }
                return 0
            }
        })

        val _DeleteLocalRef: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)
                if (log.isDebugEnabled) {
                    log.debug("DeleteLocalRef object={}", obj)
                }
                return 0
            }
        })

        val _IsSameObject: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val ref1 = context.getPointerArg(1)
                val ref2 = context.getPointerArg(2)
                if (log.isDebugEnabled) {
                    log.debug("IsSameObject ref1={}, ref2={}", ref1, ref2)
                }
                return if (ref1 === ref2 || ref1 == ref2) VM.JNI_TRUE.toLong() else VM.JNI_FALSE.toLong()
            }
        })

        val _NewLocalRef: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)
                if (obj == null) {
                    return 0
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("NewLocalRef object={}, dvmObject={}, class={}", obj, dvmObject, if (dvmObject != null) dvmObject.getObjectType() else null)
                }
                if (verbose) {
                    System.out.printf("JNIEnv->NewLocalRef(%s) was called from %s%n", dvmObject, context.getLRPointer())
                }
                return obj.toIntPeer().toLong()
            }
        })

        val _EnsureLocalCapacity: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val capacity = context.getIntArg(1)
                if (log.isDebugEnabled) {
                    log.debug("EnsureLocalCapacity capacity={}", capacity)
                }
                return 0
            }
        })

        val _AllocObject: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val dvmClass = classMap.get(clazz.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("AllocObject clazz={}, lr={}", dvmClass, context.getLRPointer())
                }
                if (dvmClass == null) {
                    throw BackendException()
                } else {
                    val obj = dvmClass.allocObject()
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->AllocObject(%s => %s) was called from %s%n", dvmClass.getClassName(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _NewObject: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("NewObject clazz={}, jmethodID={}, lr={}", dvmClass, jmethodID, context.getLRPointer())
                }
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val obj = dvmMethod.newObject(varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->NewObject(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _NewObjectV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("NewObjectV clazz={}, jmethodID={}, va_list={}, lr={}", dvmClass, jmethodID, va_list, context.getLRPointer())
                }
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val obj = dvmMethod.newObjectV(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->NewObjectV(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _NewObjectA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("NewObjectA clazz={}, jmethodID={}, jvalue={}, lr={}", dvmClass, jmethodID, jvalue, context.getLRPointer())
                }
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    val obj = dvmMethod.newObjectA(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->NewObjectA(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _GetObjectClass: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("GetObjectClass object={}, dvmObject={}", obj, dvmObject)
                }
                if (dvmObject == null) {
                    throw BackendException()
                } else {
                    val dvmClass = dvmObject.getObjectType()
                    return dvmClass.hashCode().toLong()
                }
            }
        })

        val _IsInstanceOf: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val clazz = context.getPointerArg(2)
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                if (clazz == null) {
                    throw IllegalStateException("LR=" + context.getLRPointer())
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                if (dvmObject == null || dvmClass == null) {
                    throw BackendException()
                }
                val flag = dvmObject.isInstanceOf(dvmClass)
                log.debug("IsInstanceOf object={}, clazz={}, dvmObject={}, dvmClass={}, flag={}", obj, clazz, dvmObject, dvmClass, flag)
                return if (flag) VM.JNI_TRUE.toLong() else VM.JNI_FALSE.toLong()
            }
        })

        val _GetMethodID: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val methodName = context.getPointerArg(2)!!
                val argsPointer = context.getPointerArg(3)!!
                val name = methodName.getString(0L)
                val args = argsPointer.getString(0L)
                if (log.isDebugEnabled) {
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

        val _CallObjectMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallObjectMethod object={}, jmethodID={}", obj, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val ret = dvmMethod.callObjectMethod(dvmObject, varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallObjectMethod(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    return addLocalObject(ret).toLong()
                }
            }
        })

        val _CallObjectMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallObjectMethodV object={}, jmethodID={}, va_list={}, lr={}", obj, jmethodID, va_list, context.getLRPointer())
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException("dvmObject=$dvmObject, dvmClass=$dvmClass, jmethodID=$jmethodID")
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val obj2 = dvmMethod.callObjectMethodV(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallObjectMethodV(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), obj2, context.getLRPointer())
                    }
                    return addLocalObject(obj2).toLong()
                }
            }
        })

        val _CallObjectMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallObjectMethodA object={}, jmethodID={}, jvalue={}, lr={}", obj, jmethodID, jvalue, context.getLRPointer())
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException("dvmObject=$dvmObject, dvmClass=$dvmClass, jmethodID=$jmethodID")
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    val obj2 = dvmMethod.callObjectMethodA(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallObjectMethodA(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), obj2, context.getLRPointer())
                    }
                    return addLocalObject(obj2).toLong()
                }
            }
        })

        val _CallBooleanMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallBooleanMethod object={}, jmethodID={}", obj, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val ret = dvmMethod.callBooleanMethod(dvmObject, varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallBooleanMethod(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    return if (ret) VM.JNI_TRUE.toLong() else VM.JNI_FALSE.toLong()
                }
            }
        })

        val _CallBooleanMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallBooleanMethodV object={}, jmethodID={}, va_list={}", obj, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val ret = dvmMethod.callBooleanMethodV(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallBooleanMethodV(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return if (ret) VM.JNI_TRUE.toLong() else VM.JNI_FALSE.toLong()
                }
            }
        })

        val _CallBooleanMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallBooleanMethodA object={}, jmethodID={}, jvalue={}", obj, jmethodID, jvalue)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    val ret = dvmMethod.callBooleanMethodA(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallBooleanMethodA(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return if (ret) VM.JNI_TRUE.toLong() else VM.JNI_FALSE.toLong()
                }
            }
        })

        val _CallByteMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallByteMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallByteMethodV object={}, jmethodID={}, va_list={}", obj, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val ret = dvmMethod.callByteMethodV(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallByteMethodV(%s, %s(%s) => 0x%x) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallByteMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallCharMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallCharMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val ret = dvmMethod.callCharMethodV(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallCharMethodV(%s, %s(%s) => %c) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return (ret.code.toLong() and 0xffffL)
                }
            }
        })

        val _CallCharMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallShortMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallShortMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallShortMethodV object={}, jmethodID={}, va_list={}", obj, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val ret = dvmMethod.callShortMethodV(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallShortMethodV(%s, %s(%s) => 0x%x) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallShortMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallIntMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallIntMethod object={}, jmethodID={}", obj, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val ret = dvmMethod.callIntMethod(dvmObject, varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallIntMethod(%s, %s(%s) => 0x%x) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallIntMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallIntMethodV object={}, jmethodID={}, va_list={}", obj, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val ret = dvmMethod.callIntMethodV(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallIntMethodV(%s, %s(%s) => 0x%x) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallIntMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallIntMethodA object={}, jmethodID={}, jvalue={}", obj, jmethodID, jvalue)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    val ret = dvmMethod.callIntMethodA(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallIntMethodA(%s, %s(%s) => 0x%x) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallLongMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallLongMethod object={}, jmethodID={}", obj, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val ret = dvmMethod.callLongMethod(dvmObject, varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallLongMethod(%s, %s(%s) => 0x%xL) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret
                }
            }
        })

        val _CallLongMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallLongMethodV object={}, jmethodID={}, va_list={}", obj, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val ret = dvmMethod.callLongMethodV(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallLongMethodV(%s, %s(%s) => 0x%xL) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret
                }
            }
        })

        val _CallLongMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallLongMethodA object={}, jmethodID={}, jvalue={}", obj, jmethodID, jvalue)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    val ret = dvmMethod.callLongMethodA(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallLongMethodA(%s, %s(%s) => 0x%xL) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret
                }
            }
        })

        val _CallFloatMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallFloatMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallFloatMethodV object={}, jmethodID={}, va_list={}", obj, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val ret = dvmMethod.callFloatMethodV(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallFloatMethodV(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(16)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putFloat(ret)
                    emulator.getBackend().reg_write_vector(Arm64Const.UC_ARM64_REG_Q0, buffer.array())
                    return context.getLongArg(0)
                }
            }
        })

        val _CallFloatMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallDoubleMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallDoubleMethod object={}, jmethodID={}", obj, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val ret = dvmMethod.callDoubleMethod(dvmObject, varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallDoubleMethod(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(16)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putDouble(ret)
                    emulator.getBackend().reg_write_vector(Arm64Const.UC_ARM64_REG_Q0, buffer.array())
                    return context.getLongArg(0)
                }
            }
        })

        val _CallDoubleMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallDoubleMethodV object={}, jmethodID={}, va_list={}", obj, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val ret = dvmMethod.callDoubleMethodV(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallDoubleMethodV(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(16)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putDouble(ret)
                    emulator.getBackend().reg_write_vector(Arm64Const.UC_ARM64_REG_Q0, buffer.array())
                    return context.getLongArg(0)
                }
            }
        })

        val _CallDoubleMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallDoubleMethodA object={}, jmethodID={}, jvalue={}, lr={}", obj, jmethodID, jvalue, context.getLRPointer())
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException("dvmObject=$dvmObject, dvmClass=$dvmClass, jmethodID=$jmethodID")
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    val ret = dvmMethod.callDoubleMethod(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallDoubleMethodA(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(16)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putDouble(ret)
                    emulator.getBackend().reg_write_vector(Arm64Const.UC_ARM64_REG_Q0, buffer.array())
                    return context.getLongArg(0)
                }
            }
        })

        val _CallVoidMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallVoidMethod object={}, jmethodID={}", obj, jmethodID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    dvmMethod.callVoidMethod(dvmObject, varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallVoidMethod(%s, %s(%s)) was called from %s%n", dvmObject, dvmMethod.methodName, varArg.formatArgs(), context.getLRPointer())
                    }
                    return 0
                }
            }
        })

        val _CallVoidMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallVoidMethodV object={}, jmethodID={}, va_list={}", obj, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    dvmMethod.callVoidMethodV(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallVoidMethodV(%s, %s(%s)) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return 0
                }
            }
        })

        val _CallVoidMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallVoidMethodA object={}, jmethodID={}, jvalue={}", obj, jmethodID, jvalue)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    dvmMethod.callVoidMethodA(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallVoidMethodA(%s, %s(%s)) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return 0
                }
            }
        })

        val _CallNonvirtualObjectMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualObjectMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualObjectMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualBooleanMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualBooleanMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualBooleanMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val clazz = context.getPointerArg(2)!!
                val jmethodID = context.getPointerArg(3)!!
                val jvalue = context.getPointerArg(4)
                if (log.isDebugEnabled) {
                    log.debug("CallNonvirtualBooleanMethodA object={}, clazz={}, jmethodID={}, jvalue={}", obj, clazz, jmethodID, jvalue)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    if (dvmMethod.isConstructor()) {
                        throw IllegalStateException()
                    }
                    val ret = dvmMethod.callBooleanMethodA(dvmObject, vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallNonvirtualBooleanMethodA(%s, %s(%s) => %s) was called from %s%n", dvmObject, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return if (ret) VM.JNI_TRUE.toLong() else VM.JNI_FALSE.toLong()
                }
            }
        })

        val _CallNonvirtualByteMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualByteMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualByteMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualCharMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualCharMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualCharMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualShortMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualShortMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualShortMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualIntMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualIntMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualIntMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualLongMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualLongMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualLongMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualFloatMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualFloatMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualFloatMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualDoubleMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualDoubleMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualDoubleMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualVoidMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallNonvirtualVoidMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val clazz = context.getPointerArg(2)!!
                val jmethodID = context.getPointerArg(3)!!
                val va_list = context.getPointerArg(4)
                if (log.isDebugEnabled) {
                    log.debug("CallNonvirtualVoidMethodV object={}, clazz={}, jmethodID={}, va_list={}", obj, clazz, jmethodID, va_list)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    if (dvmMethod.isConstructor()) {
                        val obj2 = dvmMethod.newObjectV(vaList)
                        Objects.requireNonNull(dvmObject).setValue(obj2.getValue())
                    } else {
                        dvmMethod.callVoidMethodV(dvmObject, vaList)
                    }
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallNonvirtualVoidMethodV(%s, %s, %s(%s)) was called from %s%n", dvmObject, dvmClass!!.getClassName(), dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return 0
                }
            }
        })

        val _CallNonVirtualVoidMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val clazz = context.getPointerArg(2)!!
                val jmethodID = context.getPointerArg(3)!!
                val jvalue = context.getPointerArg(4)
                if (log.isDebugEnabled) {
                    log.debug("CallNonVirtualVoidMethodA object={}, clazz={}, jmethodID={}, jvalue={}", obj, clazz, jmethodID, jvalue)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    if (dvmMethod.isConstructor()) {
                        val obj2 = dvmMethod.newObjectV(vaList)
                        Objects.requireNonNull(dvmObject).setValue(obj2.getValue())
                    } else {
                        dvmMethod.callVoidMethodA(dvmObject, vaList)
                    }
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallNonVirtualVoidMethodA(%s, %s, %s(%s)) was called from %s%n", dvmObject, dvmClass!!.getClassName(), dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return 0
                }
            }
        })

        val _GetFieldID: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val fieldName = context.getPointerArg(2)!!
                val argsPointer = context.getPointerArg(3)!!
                val name = fieldName.getString(0L)
                val args = argsPointer.getString(0L)
                if (log.isDebugEnabled) {
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

        val _GetObjectField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetObjectField object={}, jfieldID={}", obj, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val obj2 = dvmField.getObjectField(dvmObject)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetObjectField(%s, %s %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, dvmField.fieldType, obj2, context.getLRPointer())
                    }
                    return addLocalObject(obj2).toLong()
                }
            }
        })

        val _GetBooleanField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetBooleanField object={}, jfieldID={}", obj, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getBooleanField(dvmObject)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetBooleanField(%s, %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, ret == VM.JNI_TRUE, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _GetByteField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetByteField object={}, jfieldID={}", obj, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getByteField(dvmObject)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetByteField(%s, %s => 0x%x) was called from %s%n", dvmObject, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _GetCharField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetShortField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetIntField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetIntField object={}, jfieldID={}", obj, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getIntField(dvmObject)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetIntField(%s, %s => 0x%x) was called from %s%n", dvmObject, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _GetLongField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetLongField object={}, jfieldID={}", obj, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getLongField(dvmObject)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetLongField(%s, %s => 0x%x) was called from %s%n", dvmObject, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    return ret
                }
            }
        })

        val _GetFloatField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetFloatField object={}, jfieldID={}", obj, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getFloatField(dvmObject)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetFloatField(%s, %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(16)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putFloat(ret)
                    emulator.getBackend().reg_write_vector(Arm64Const.UC_ARM64_REG_Q0, buffer.array())
                    return context.getLongArg(0)
                }
            }
        })

        val _GetDoubleField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetDoubleField object={}, jfieldID={}", obj, jfieldID)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getDoubleField(dvmObject)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetDoubleField(%s, %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(16)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putDouble(ret)
                    emulator.getBackend().reg_write_vector(Arm64Const.UC_ARM64_REG_Q0, buffer.array())
                    return context.getLongArg(0)
                }
            }
        })

        val _SetObjectField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("SetObjectField object={}, jfieldID={}, value={}", obj, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val obj2 = if (value == null) null else getObjectOrNull(value.toIntPeer())
                    dvmField.setObjectField(dvmObject, obj2)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetObjectField(%s, %s %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, dvmField.fieldType, obj2, context.getLRPointer())
                    }
                }
                return 0
            }
        })

        val _SetBooleanField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getIntArg(3)
                if (log.isDebugEnabled) {
                    log.debug("SetBooleanField object={}, jfieldID={}, value={}", obj, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val flag = BaseVM.valueOf(value)
                    dvmField.setBooleanField(dvmObject, flag)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetBooleanField(%s, %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, flag, context.getLRPointer())
                    }
                }
                return 0
            }
        })

        val _SetByteField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetCharField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetShortField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetIntField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getIntArg(3)
                if (log.isDebugEnabled) {
                    log.debug("SetIntField object={}, jfieldID={}, value={}", obj, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    dvmField.setIntField(dvmObject, value)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetIntField(%s, %s => 0x%x) was called from %s%n", dvmObject, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0
            }
        })

        val _SetLongField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getLongArg(3)
                if (log.isDebugEnabled) {
                    log.debug("SetLongField object={}, jfieldID={}, value={}", obj, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    dvmField.setLongField(dvmObject, value)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetLongField(%s, %s => 0x%x) was called from %s%n", dvmObject, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0
            }
        })

        val _SetFloatField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val buffer = ByteBuffer.allocate(16)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                buffer.put(emulator.getBackend().reg_read_vector(Arm64Const.UC_ARM64_REG_Q0))
                buffer.flip()
                val value = buffer.getFloat()
                if (log.isDebugEnabled) {
                    log.debug("SetFloatField object={}, jfieldID={}, value={}", obj, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    dvmField.setFloatField(dvmObject, value)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetFloatField(%s, %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0
            }
        })

        val _SetDoubleField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val buffer = ByteBuffer.allocate(16)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                buffer.put(emulator.getBackend().reg_read_vector(Arm64Const.UC_ARM64_REG_Q0))
                buffer.flip()
                val value = buffer.getDouble()
                if (log.isDebugEnabled) {
                    log.debug("SetDoubleField object={}, jfieldID={}, value={}", obj, jfieldID, value)
                }
                val dvmObject = getObject<DvmObject<*>>(obj.toIntPeer())
                val dvmClass = if (dvmObject == null) null else dvmObject.getObjectType()
                val dvmField = if (dvmClass == null) null else dvmClass.getField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    dvmField.setDoubleField(dvmObject, value)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetDoubleField(%s, %s => %s) was called from %s%n", dvmObject, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0
            }
        })

        val _GetStaticMethodID: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val methodName = context.getPointerArg(2)!!
                val argsPointer = context.getPointerArg(3)!!
                val name = methodName.getString(0L)
                val args = argsPointer.getString(0L)
                if (log.isDebugEnabled) {
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

        val _CallStaticObjectMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallStaticObjectMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val obj = dvmMethod.callStaticObjectMethod(varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticObjectMethod(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _CallStaticObjectMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallStaticObjectMethodV clazz={}, jmethodID={}, va_list={}", clazz, jmethodID, va_list)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val obj = dvmMethod.callStaticObjectMethodV(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticObjectMethodV(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _CallStaticObjectMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallStaticObjectMethodA clazz={}, jmethodID={}, jvalue={}", clazz, jmethodID, jvalue)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    val obj = dvmMethod.callStaticObjectMethodA(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticObjectMethodA(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _CallStaticBooleanMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallStaticBooleanMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val ret = dvmMethod.CallStaticBooleanMethod(varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticBooleanMethod(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    return if (ret) VM.JNI_TRUE.toLong() else VM.JNI_FALSE.toLong()
                }
            }
        })

        val _CallStaticBooleanMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallStaticBooleanMethodV clazz={}, jmethodID={}, va_list={}", clazz, jmethodID, va_list)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val ret = dvmMethod.callStaticBooleanMethodV(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticBooleanMethodV(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return if (ret) VM.JNI_TRUE.toLong() else VM.JNI_FALSE.toLong()
                }
            }
        })

        val _CallStaticBooleanMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallStaticBooleanMethodA clazz={}, jmethodID={}, jvalue={}", clazz, jmethodID, jvalue)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    val ret = dvmMethod.callStaticBooleanMethodV(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticBooleanMethodA(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return if (ret) VM.JNI_TRUE.toLong() else VM.JNI_FALSE.toLong()
                }
            }
        })

        val _CallStaticByteMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticByteMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticByteMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticCharMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticCharMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticCharMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticShortMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticShortMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticShortMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticIntMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallStaticIntMethodV clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val ret = dvmMethod.callStaticIntMethod(varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticIntMethod(%s, %s(%s) => 0x%x) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallStaticIntMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallStaticIntMethodV clazz={}, jmethodID={}, va_list={}", clazz, jmethodID, va_list)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val ret = dvmMethod.callStaticIntMethodV(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticIntMethodV(%s, %s(%s) => 0x%x) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallStaticIntMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallStaticIntMethodA clazz={}, jmethodID={}, jvalue={}", clazz, jmethodID, jvalue)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    val ret = dvmMethod.callStaticIntMethodV(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticIntMethodA(%s, %s(%s) => 0x%x) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _CallStaticLongMethod: Pointer = svcMemory.registerSvc(object : Arm64Hook() {
            @Throws(NestedRun::class)
            override fun hook(emulator: Emulator<*>): HookStatus {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallStaticLongMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val value = dvmMethod.callStaticLongMethod(varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticLongMethod(%s, %s(%s)) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), context.getLRPointer())
                    }
                    return HookStatus.LR(emulator, value)
                }
            }
        })

        val _CallStaticLongMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallStaticLongMethodV clazz={}, jmethodID={}, va_list={}, lr={}", clazz, jmethodID, va_list, context.getLRPointer())
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    val ret = dvmMethod.callStaticLongMethodV(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticLongMethodV(%s, %s(%s) => 0x%xL) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret
                }
            }
        })

        val _CallStaticLongMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallStaticLongMethodA clazz={}, jmethodID={}, jvalue={}", clazz, jmethodID, jvalue)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    val ret = dvmMethod.callStaticLongMethodA(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticLongMethodA(%s, %s(%s) => 0x%x) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), ret, context.getLRPointer())
                    }
                    return ret
                }
            }
        })

        val _CallStaticFloatMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallStaticFloatMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val ret = dvmMethod.callStaticFloatMethod(varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticFloatMethod(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(16)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putFloat(ret)
                    emulator.getBackend().reg_write_vector(Arm64Const.UC_ARM64_REG_Q0, buffer.array())
                    return context.getLongArg(0)
                }
            }
        })

        val _CallStaticFloatMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticFloatMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticDoubleMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallStaticDoubleMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    val ret = dvmMethod.callStaticDoubleMethod(varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticDoubleMethod(%s, %s(%s) => %s) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(16)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putDouble(ret)
                    emulator.getBackend().reg_write_vector(Arm64Const.UC_ARM64_REG_Q0, buffer.array())
                    return context.getLongArg(0)
                }
            }
        })

        val _CallStaticDoubleMethodV: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticDoubleMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _CallStaticVoidMethod: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("CallStaticVoidMethod clazz={}, jmethodID={}", clazz, jmethodID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val varArg = ArmVarArg.create(emulator, this@DalvikVM64, dvmMethod)
                    dvmMethod.callStaticVoidMethod(varArg)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticVoidMethod(%s, %s(%s)) was called from %s%n", dvmClass, dvmMethod.methodName, varArg.formatArgs(), context.getLRPointer())
                    }
                    return 0
                }
            }
        })

        val _CallStaticVoidMethodV: Pointer = svcMemory.registerSvc(object : Arm64Hook() {
            override fun hook(emulator: Emulator<*>): HookStatus {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val va_list = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallStaticVoidMethodV clazz={}, jmethodID={}, va_list={}", clazz, jmethodID, va_list)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = VaList64(emulator, this@DalvikVM64, va_list!!, dvmMethod)
                    dvmMethod.callStaticVoidMethodV(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticVoidMethodV(%s, %s(%s)) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return HookStatus.LR(emulator, 0)
                }
            }
        })

        val _CallStaticVoidMethodA: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jmethodID = context.getPointerArg(2)!!
                val jvalue = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("CallStaticVoidMethodA clazz={}, jmethodID={}, jvalue={}", clazz, jmethodID, jvalue)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmMethod = if (dvmClass == null) null else dvmClass.getStaticMethod(jmethodID.toIntPeer())
                if (dvmMethod == null) {
                    throw BackendException()
                } else {
                    val vaList: VaList = JValueList(this@DalvikVM64, jvalue!!, dvmMethod)
                    dvmMethod.callStaticVoidMethodA(vaList)
                    if (verbose || verboseMethodOperation) {
                        System.out.printf("JNIEnv->CallStaticVoidMethodA(%s, %s(%s)) was called from %s%n", dvmClass, dvmMethod.methodName, vaList.formatArgs(), context.getLRPointer())
                    }
                    return 0
                }
            }
        })

        val _GetStaticFieldID: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val fieldName = context.getPointerArg(2)!!
                val argsPointer = context.getPointerArg(3)!!
                val name = fieldName.getString(0L)
                val args = argsPointer.getString(0L)
                if (log.isDebugEnabled) {
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

        val _GetStaticObjectField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetStaticObjectField clazz={}, jfieldID={}", clazz, jfieldID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val obj = dvmField.getStaticObjectField()
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetStaticObjectField(%s, %s %s => %s) was called from %s%n", dvmClass, dvmField.fieldName, dvmField.fieldType, obj, context.getLRPointer())
                    }
                    return addLocalObject(obj).toLong()
                }
            }
        })

        val _GetStaticBooleanField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetStaticBooleanField clazz={}, jfieldID={}", clazz, jfieldID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getStaticBooleanField()
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetStaticBooleanField(%s, %s => %s) was called from %s%n", dvmClass, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    return if (ret) VM.JNI_TRUE.toLong() else VM.JNI_FALSE.toLong()
                }
            }
        })

        val _GetStaticByteField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetStaticByteField clazz={}, jfieldID={}", clazz, jfieldID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getStaticByteField()
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetStaticByteField(%s, %s => %s) was called from %s%n", dvmClass, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _GetStaticCharField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetStaticShortField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetStaticIntField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetStaticIntField clazz={}, jfieldID={}", clazz, jfieldID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getStaticIntField()
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetStaticIntField(%s, %s => 0x%x) was called from %s%n", dvmClass, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    return ret.toLong()
                }
            }
        })

        val _GetStaticLongField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<Arm64RegisterContext>()
                val clazz = context.getXPointer(1)
                val jfieldID = context.getXPointer(2)
                if (log.isDebugEnabled) {
                    log.debug("GetStaticLongField clazz={}, jfieldID={}", clazz, jfieldID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getStaticLongField()
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetStaticLongField(%s, %s => 0x%x) was called from %s%n", dvmClass, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    return ret
                }
            }
        })

        val _GetStaticFloatField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetStaticDoubleField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                if (log.isDebugEnabled) {
                    log.debug("GetStaticDoubleField clazz={}, jfieldID={}", clazz, jfieldID)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException()
                } else {
                    val ret = dvmField.getStaticDoubleField()
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->GetStaticDoubleField(%s, %s => %s) was called from %s%n", dvmClass, dvmField.fieldName, ret, context.getLRPointer())
                    }
                    val buffer = ByteBuffer.allocate(16)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.putDouble(ret)
                    emulator.getBackend().reg_write_vector(Arm64Const.UC_ARM64_REG_Q0, buffer.array())
                    return context.getLongArg(0)
                }
            }
        })

        val _SetStaticObjectField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("SetStaticObjectField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmObject = if (value == null) null else getObject<DvmObject<*>>(value.toIntPeer())
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=$dvmClass")
                } else {
                    dvmField.setStaticObjectField(dvmObject)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetStaticObjectField(%s, %s, %s) was called from %s%n", dvmClass, dvmField.fieldName, dvmObject, context.getLRPointer())
                    }
                }
                return 0
            }
        })

        val _SetStaticBooleanField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getIntArg(3)
                if (log.isDebugEnabled) {
                    log.debug("SetStaticBooleanField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=$dvmClass")
                } else {
                    val flag = BaseVM.valueOf(value)
                    dvmField.setStaticBooleanField(flag)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetStaticBooleanField(%s, %s, %s) was called from %s%n", dvmClass, dvmField.fieldName, flag, context.getLRPointer())
                    }
                }
                return 0
            }
        })

        val _SetStaticByteField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetStaticCharField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetStaticShortField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetStaticIntField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getIntArg(3)
                if (log.isDebugEnabled) {
                    log.debug("SetStaticIntField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=$dvmClass")
                } else {
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetStaticIntField(%s, %s, 0x%x) was called from %s%n", dvmClass, dvmField.fieldName, value, context.getLRPointer())
                    }
                    dvmField.setStaticIntField(value)
                }
                return 0
            }
        })

        val _SetStaticLongField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val value = context.getLongArg(3)
                if (log.isDebugEnabled) {
                    log.debug("SetStaticLongField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=$dvmClass")
                } else {
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetStaticLongField(%s, %s, 0x%x) was called from %s%n", dvmClass, dvmField.fieldName, value, context.getLRPointer())
                    }
                    dvmField.setStaticLongField(value)
                }
                return 0
            }
        })

        val _GetStringUTFLength: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val string = getObject<DvmObject<*>>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("GetStringUTFLength string={}, lr={}", string, context.getLRPointer())
                }
                val value = Objects.requireNonNull(string).getValue() as String
                if (verbose || verboseMethodOperation) {
                    System.out.printf("JNIEnv->GetStringUTFLength(%s) was called from %s%n", string, context.getLRPointer())
                }
                val data = value.toByteArray(StandardCharsets.UTF_8)
                return data.size.toLong()
            }
        })

        val _GetStringUTFChars: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val string = getObject<StringObject>(obj.toIntPeer())
                if (isCopy != null) {
                    isCopy.setInt(0L, VM.JNI_TRUE)
                }
                val value = Objects.requireNonNull(string).getValue()
                if (verbose || verboseMethodOperation) {
                    System.out.printf("JNIEnv->GetStringUtfChars(%s) was called from %s%n", string, context.getLRPointer())
                }
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                if (log.isDebugEnabled) {
                    log.debug("GetStringUTFChars string={}, isCopy={}, value={}, lr={}", string, isCopy, value, context.getLRPointer())
                }
                val data = Arrays.copyOf(bytes, bytes.size + 1)
                val pointer = string!!.allocateMemoryBlock(emulator, data.size)
                pointer.write(0L, data, 0, data.size)
                return pointer.toIntPeer().toLong()
            }
        })

        val _ReleaseStringUTFChars: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val string = getObject<StringObject>(obj.toIntPeer())
                if (verbose) {
                    System.out.printf("JNIEnv->ReleaseStringUTFChars(%s) was called from %s%n", string, context.getLRPointer())
                }
                if (log.isDebugEnabled) {
                    log.debug("ReleaseStringUTFChars string={}, pointer={}, lr={}", string, pointer, context.getLRPointer())
                }
                Objects.requireNonNull(string).freeMemoryBlock(pointer)
                return 0
            }
        })

        val _GetArrayLength: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val pointer = context.getPointerArg(1)!!
                val array = Objects.requireNonNull(getObject<DvmObject<*>>(pointer.toIntPeer()) as com.vortexdbg.linux.android.dvm.Array<*>)
                if (log.isDebugEnabled) {
                    log.debug("GetArrayLength array={}", array)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetArrayLength(%s => %s) was called from %s%n", array, array.length(), context.getLRPointer())
                }
                return array.length().toLong()
            }
        })

        val _NewObjectArray: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                val elementClass = context.getPointerArg(2)!!
                val initialElement = context.getPointerArg(3)
                if (log.isDebugEnabled) {
                    log.debug("NewObjectArray size={}, elementClass={}, initialElement={}", size, elementClass, initialElement)
                }
                val dvmClass = classMap.get(elementClass.toIntPeer())
                if (dvmClass == null) {
                    throw BackendException("elementClass=$elementClass")
                }

                val obj = if (size == 0) null else if (initialElement == null) null else getObject<DvmObject<*>>(initialElement.toIntPeer())
                val array = arrayOfNulls<DvmObject<*>>(size)
                Arrays.fill(array, obj)
                val arrayObject = ArrayObject(*array)
                if (verbose || verboseMethodOperation) {
                    System.out.printf("JNIEnv->NewObjectArray(%s => %s) was called from %s%n", arrayObject, size, context.getLRPointer())
                }
                return addLocalObject(arrayObject).toLong()
            }
        })

        val _GetObjectArrayElement: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val index = context.getIntArg(2)
                val array = getObject<ArrayObject>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("GetObjectArrayElement array={}, index={}", array, index)
                }
                val obj2 = Objects.requireNonNull(array).getValue()[index]
                if (verbose) {
                    System.out.printf("JNIEnv->GetObjectArrayElement(%s, %d) => %s was called from %s%n", array, index, obj2, context.getLRPointer())
                }
                return addLocalObject(obj2).toLong()
            }
        })

        val _SetObjectArrayElement: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val index = context.getIntArg(2)
                val element = context.getPointerArg(3)
                val array = getObject<ArrayObject>(obj.toIntPeer())
                val obj2 = if (element == null) null else getObject<DvmObject<*>>(element.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("setObjectArrayElement array={}, index={}, obj={}", array, index, obj2)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->SetObjectArrayElement(%s, %d) => %s was called from %s%n", array, index, obj2, context.getLRPointer())
                }
                val objs = Objects.requireNonNull(array).getValue()
                objs[index] = obj2
                return 0
            }
        })

        val _NewBooleanArray: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _NewByteArray: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                if (log.isDebugEnabled) {
                    log.debug("NewByteArray size={}", size)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->NewByteArray(%d) was called from %s%n", size, context.getLRPointer())
                }
                return addLocalObject(ByteArray(this@DalvikVM64, kotlin.ByteArray(size))).toLong()
            }
        })

        val _NewCharArray: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                if (log.isDebugEnabled) {
                    log.debug("NewCharArray size={}", size)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->NewCharArray(%d) was called from %s%n", size, context.getLRPointer())
                }
                return addLocalObject(CharArray(this@DalvikVM64, kotlin.CharArray(size))).toLong()
            }
        })

        val _NewShortArray: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _NewIntArray: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                if (log.isDebugEnabled) {
                    log.debug("NewIntArray size={}", size)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->NewIntArray(%d) was called from %s%n", size, context.getLRPointer())
                }
                return addLocalObject(IntArray(this@DalvikVM64, kotlin.IntArray(size))).toLong()
            }
        })

        val _NewLongArray: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                if (log.isDebugEnabled) {
                    log.debug("NewLongArray size={}", size)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->NewLongArray(%d) was called from %s%n", size, context.getLRPointer())
                }
                return addLocalObject(LongArray(this@DalvikVM64, kotlin.LongArray(size))).toLong()
            }
        })

        val _NewFloatArray: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                if (log.isDebugEnabled) {
                    log.debug("NewFloatArray size={}", size)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->NewFloatArray(%d) was called from %s%n", size, context.getLRPointer())
                }
                return addLocalObject(FloatArray(this@DalvikVM64, kotlin.FloatArray(size))).toLong()
            }
        })

        val _NewDoubleArray: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val size = context.getIntArg(1)
                if (log.isDebugEnabled) {
                    log.debug("_NewDoubleArray size={}", size)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->NewDoubleArray(%d) was called from %s%n", size, context.getLRPointer())
                }
                return addLocalObject(DoubleArray(this@DalvikVM64, kotlin.DoubleArray(size))).toLong()
            }
        })

        val _GetBooleanArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetByteArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val arrayPointer = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                if (log.isDebugEnabled) {
                    log.debug("GetByteArrayElements arrayPointer={}, isCopy={}", arrayPointer, isCopy)
                }
                if (isCopy != null) {
                    isCopy.setInt(0L, VM.JNI_TRUE)
                }
                val array = getObject<ByteArray>(arrayPointer.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetByteArrayElements(%s) => %s was called from %s%n", isCopy != null, array, context.getLRPointer())
                }
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).peer
            }
        })

        val _GetCharArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val array = getObject<CharArray>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("GetCharArrayElements array={}, isCopy={}", array, isCopy)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetCharArrayElements(%s) => %s was called from %s%n", isCopy != null, array, context.getLRPointer())
                }
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).peer
            }
        })

        val _GetShortArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetIntArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val array = getObject<IntArray>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("GetIntArrayElements array={}, isCopy={}", array, isCopy)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetIntArrayElements(%s) => %s was called from %s%n", isCopy != null, array, context.getLRPointer())
                }
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).peer
            }
        })

        val _SetStaticFloatField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val buffer = ByteBuffer.allocate(16)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                buffer.put(emulator.getBackend().reg_read_vector(Arm64Const.UC_ARM64_REG_Q0))
                buffer.flip()
                val value = buffer.getFloat()
                if (log.isDebugEnabled) {
                    log.debug("SetStaticFloatField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=$dvmClass")
                } else {
                    dvmField.setStaticFloatField(value)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetStaticFloatField(%s, %s, %s) was called from %s%n", dvmClass, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0
            }
        })

        val _SetStaticDoubleField: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val jfieldID = context.getPointerArg(2)!!
                val buffer = ByteBuffer.allocate(16)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                buffer.put(emulator.getBackend().reg_read_vector(Arm64Const.UC_ARM64_REG_Q0))
                buffer.flip()
                val value = buffer.getDouble()
                if (log.isDebugEnabled) {
                    log.debug("SetStaticDoubleField clazz={}, jfieldID={}, value={}", clazz, jfieldID, value)
                }
                val dvmClass = classMap.get(clazz.toIntPeer())
                val dvmField = if (dvmClass == null) null else dvmClass.getStaticField(jfieldID.toIntPeer())
                if (dvmField == null) {
                    throw BackendException("dvmClass=$dvmClass")
                } else {
                    dvmField.setStaticDoubleField(value)
                    if (verbose || verboseFieldOperation) {
                        System.out.printf("JNIEnv->SetStaticDoubleField(%s, %s, %s) was called from %s%n", dvmClass, dvmField.fieldName, value, context.getLRPointer())
                    }
                }
                return 0
            }
        })

        val _NewString: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
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
                log.debug("NewString unicodeChars={}, len={}, string={}", unicodeChars, len, string)
                if (verbose || verboseMethodOperation) {
                    System.out.printf("JNIEnv->NewString(\"%s\") was called from %s%n", string, context.getLRPointer())
                }
                return addLocalObject(StringObject(this@DalvikVM64, string)).toLong()
            }
        })

        val _GetStringLength: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val string = getObject<DvmObject<*>>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("GetStringLength string={}, lr={}", string, context.getLRPointer())
                }
                val value = Objects.requireNonNull(string).getValue() as String
                return value.length.toLong()
            }
        })

        val _GetStringChars: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val string = getObject<StringObject>(obj.toIntPeer())
                if (isCopy != null) {
                    isCopy.setInt(0L, VM.JNI_TRUE)
                }
                val value = Objects.requireNonNull(string).getValue()
                val bytes = kotlin.ByteArray(value.length * 2)
                val buffer = ByteBuffer.wrap(bytes)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (c in value.toCharArray()) {
                    buffer.putChar(c)
                }
                if (log.isDebugEnabled) {
                    log.debug("GetStringChars string={}, isCopy={}, value={}, lr={}", string, isCopy, value, context.getLRPointer())
                }
                if (verbose || verboseMethodOperation) {
                    System.out.printf("JNIEnv->GetStringUTFChars(\"%s\") was called from %s%n", value, context.getLRPointer())
                }
                val data = Arrays.copyOf(bytes, bytes.size + 1)
                val pointer = string!!.allocateMemoryBlock(emulator, data.size)
                pointer.write(0L, data, 0, data.size)
                return pointer.toIntPeer().toLong()
            }
        })

        val _ReleaseStringChars: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val string = getObject<StringObject>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("ReleaseStringChars string={}, pointer={}, lr={}", string, pointer, context.getLRPointer())
                }
                Objects.requireNonNull(string).freeMemoryBlock(pointer)
                return 0
            }
        })

        val _NewStringUTF: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val bytes = context.getPointerArg(1)
                if (bytes == null) {
                    return VM.JNI_NULL.toLong()
                }

                val string = bytes.getString(0L)
                if (log.isDebugEnabled) {
                    log.debug("NewStringUTF bytes={}, string={}", bytes, string)
                }
                if (verbose || verboseMethodOperation) {
                    System.out.printf("JNIEnv->NewStringUTF(\"%s\") was called from %s%n", string, context.getLRPointer())
                }
                return addLocalObject(StringObject(this@DalvikVM64, string)).toLong()
            }
        })

        val _GetLongArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val array = getObject<LongArray>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("GetLongArrayElements array={}, isCopy={}", array, isCopy)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetLongArrayElements(%s) => %s was called from %s%n", isCopy != null, array, context.getLRPointer())
                }
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).peer
            }
        })

        val _GetFloatArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val array = getObject<FloatArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetFloatArrayElements(%s) => %s was called from %s%n", isCopy != null, array, context.getLRPointer())
                }
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).peer
            }
        })

        val _GetDoubleArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ReleaseBooleanArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ReleaseByteArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val arrayPointer = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val mode = context.getIntArg(3)
                if (log.isDebugEnabled) {
                    log.debug("ReleaseByteArrayElements arrayPointer={}, pointer={}, mode={}", arrayPointer, pointer, mode)
                }
                val array = getObject<ByteArray>(arrayPointer.toIntPeer())
                Objects.requireNonNull(array)._ReleaseArrayCritical(pointer!!, mode)
                return 0
            }
        })

        val _ReleaseCharArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val mode = context.getIntArg(3)
                val array = getObjectOrNull(obj.toIntPeer()) as? CharArray
                if (array != null && pointer != null) {
                    // The commit (setValue via read(ShortArray)) works; freeMemoryBlock currently throws
                    // AbstractMethodError on the guest pointer. Buffer-free is best-effort, so swallow it
                    // to not abort the JNI call. ponytail: fix BaseArray.freeMemoryBlock for the char path.
                    try { array._ReleaseArrayCritical(pointer, mode) } catch (e: AbstractMethodError) { }
                }
                return 0
            }
        })

        val _ReleaseShortArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ReleaseIntArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val mode = context.getIntArg(3)
                val array = getObject<IntArray>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("ReleaseIntArrayElements array={}, pointer={}, mode={}", array, pointer, mode)
                }
                Objects.requireNonNull(array)._ReleaseArrayCritical(pointer!!, mode)
                return 0
            }
        })

        val _ReleaseLongArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val mode = context.getIntArg(3)
                val array = getObject<LongArray>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("ReleaseLongArrayElements array={}, pointer={}, mode={}", array, pointer, mode)
                }
                Objects.requireNonNull(array)._ReleaseArrayCritical(pointer!!, mode)
                return 0
            }
        })

        val _ReleaseFloatArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val mode = context.getIntArg(3)
                val array = getObject<FloatArray>(obj.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("ReleaseByteArrayElements array={}, pointer={}, mode={}", array, pointer, mode)
                }
                Objects.requireNonNull(array)._ReleaseArrayCritical(pointer!!, mode)
                return 0
            }
        })

        val _ReleaseDoubleArrayElements: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetBooleanArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetByteArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<ByteArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetByteArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(Objects.requireNonNull(array).getValue(), start, start + length)
                if (log.isDebugEnabled) {
                    Inspector.inspect(data, "GetByteArrayRegion array=$array, start=$start, length=$length, buf=$buf")
                }
                buf.write(0L, data, 0, data.size)
                return 0
            }
        })

        val _GetCharArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<CharArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetCharArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(Objects.requireNonNull(array).getValue(), start, start + length)
                if (log.isDebugEnabled) {
                    log.debug("GetCharArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                val shorts = kotlin.ShortArray(data.size) { data[it].code.toShort() }   // jchar = 2 bytes
                buf.write(0L, shorts, 0, shorts.size)
                return 0
            }
        })

        val _GetShortArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<ShortArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetShortArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(Objects.requireNonNull(array).getValue(), start, start + length)
                if (log.isDebugEnabled) {
                    log.debug("GetShortArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                buf.write(0L, data, 0, data.size)
                return 0
            }
        })

        val _GetIntArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetLongArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<LongArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetLongArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(Objects.requireNonNull(array).getValue(), start, start + length)
                if (log.isDebugEnabled) {
                    log.debug("GetLongArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                buf.write(0L, data, 0, data.size)
                return 0
            }
        })

        val _GetFloatArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetDoubleArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<DoubleArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->GetDoubleArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(Objects.requireNonNull(array).getValue(), start, start + length)
                if (log.isDebugEnabled) {
                    log.debug("GetDoubleArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                buf.write(0L, data, 0, data.size)
                return 0
            }
        })

        val _SetBooleanArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetByteArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<ByteArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->SetByteArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = buf.getByteArray(0L, length)
                if (log.isDebugEnabled) {
                    if (data.size > 1024) {
                        Inspector.inspect(Arrays.copyOf(data, 1024), "SetByteArrayRegion array=$array, start=$start, length=$length, buf=$buf")
                    } else {
                        Inspector.inspect(data, "SetByteArrayRegion array=$array, start=$start, length=$length, buf=$buf")
                    }
                }
                Objects.requireNonNull(array).setData(start, data)
                return 0
            }
        })

        val _SetCharArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<CharArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->SetCharArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val shorts = buf.getShortArray(0L, length)                                // jchar = 2 bytes
                val data = kotlin.CharArray(shorts.size) { (shorts[it].toInt() and 0xFFFF).toChar() }
                if (log.isDebugEnabled) {
                    log.debug("SetCharArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                Objects.requireNonNull(array).setData(start, data)
                return 0
            }
        })

        val _SetShortArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _SetIntArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<IntArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->SetIntArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = buf.getIntArray(0L, length)
                if (log.isDebugEnabled) {
                    log.debug("SetIntArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                Objects.requireNonNull(array).setData(start, data)
                return 0
            }
        })

        val _SetLongArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<LongArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->SetLongArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = buf.getLongArray(0L, length)
                if (log.isDebugEnabled) {
                    log.debug("SetLongArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                Objects.requireNonNull(array).setData(start, data)
                return 0
            }
        })

        val _SetFloatArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<FloatArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->SetFloatArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = buf.getFloatArray(0L, length)
                if (log.isDebugEnabled) {
                    log.debug("SetFloatArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                Objects.requireNonNull(array).setData(start, data)
                return 0
            }
        })

        val _SetDoubleArrayRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!
                val array = getObject<DoubleArray>(obj.toIntPeer())
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->SetDoubleArrayRegion(%s, %d, %d, %s) was called from %s%n", array, start, length, buf, context.getLRPointer())
                }
                val data = buf.getDoubleArray(0L, length)
                if (log.isDebugEnabled) {
                    log.debug("SetDoubleArrayRegion array={}, start={}, length={}, buf={}", array, start, length, buf)
                }
                Objects.requireNonNull(array).setData(start, data)
                return 0
            }
        })

        val _RegisterNatives: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val clazz = context.getPointerArg(1)!!
                val methods = context.getPointerArg(2)!!
                val nMethods = context.getIntArg(3)
                val dvmClass = classMap.get(clazz.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("RegisterNatives dvmClass={}, methods={}, nMethods={}", dvmClass, methods, nMethods)
                }
                if (verbose || verboseFieldOperation) {
                    System.out.printf("JNIEnv->RegisterNatives(%s, %s, %d) was called from %s%n", dvmClass!!.getClassName(), methods, nMethods, context.getLRPointer())
                }
                for (i in 0 until nMethods) {
                    val method = methods.share(i.toLong() * emulator.getPointerSize() * 3)
                    val name = method.getPointer(0L)
                    val signature = method.getPointer(emulator.getPointerSize().toLong())
                    val fnPtr = method.getPointer(emulator.getPointerSize() * 2L)
                    val methodName = name.getString(0L)
                    val signatureValue = signature.getString(0L)
                    if (log.isDebugEnabled) {
                        log.debug("RegisterNatives dvmClass={}, name={}, signature={}, fnPtr={}", dvmClass, methodName, signatureValue, fnPtr)
                    }
                    dvmClass!!.nativesMap.put(methodName + signatureValue, fnPtr as VortexdbgPointer)

                    if (verbose || verboseMethodOperation) {
                        System.out.printf("RegisterNative(%s, %s%s, %s)%n", dvmClass.getClassName(), methodName, signatureValue, fnPtr)
                    }
                }
                return VM.JNI_OK.toLong()
            }
        })

        val _UnregisterNatives: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _MonitorEnter: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val env = context.getPointerArg(0)
                val obj = getObject<DvmObject<*>>(context.getPointerArg(1)!!.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("MonitorEnter env={}, obj={}", env, obj)
                }
                return 0
            }
        })

        val _MonitorExit: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val env = context.getPointerArg(0)
                val obj = getObject<DvmObject<*>>(context.getPointerArg(1)!!.toIntPeer())
                if (log.isDebugEnabled) {
                    log.debug("MonitorExit env={}, obj={}", env, obj)
                }
                return 0
            }
        })

        val _GetJavaVM: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val vm = context.getPointerArg(1)!!
                if (log.isDebugEnabled) {
                    log.debug("GetJavaVM vm={}", vm)
                }
                vm.setPointer(0L, _JavaVM)
                return VM.JNI_OK.toLong()
            }
        })

        val _GetStringRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!

                val string = getObject<StringObject>(obj.toIntPeer())
                val value = Objects.requireNonNull(string).getValue()
                if (verbose || verboseMethodOperation) {
                    System.out.printf("JNIEnv->GetStringRegion(%s) was called from %s%n", string, context.getLRPointer())
                }
                val bytes = kotlin.ByteArray(value.length * 2)
                val buffer = ByteBuffer.wrap(bytes)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                for (c in value.toCharArray()) {
                    buffer.putChar(c)
                }
                if (log.isDebugEnabled) {
                    log.debug("GetStringRegion string={}, value={}, start={}, length={}, buf{}, lr={}", string, value, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(bytes, start, start + length + 1)
                buf.write(0L, data, 0, data.size)
                return VM.JNI_OK.toLong()
            }
        })

        val _GetStringUTFRegion: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val start = context.getIntArg(2)
                val length = context.getIntArg(3)
                val buf = context.getPointerArg(4)!!

                val string = getObject<StringObject>(obj.toIntPeer())
                val value = Objects.requireNonNull(string).getValue()
                if (verbose || verboseMethodOperation) {
                    System.out.printf("JNIEnv->GetStringUTFRegion(%s) was called from %s%n", string, context.getLRPointer())
                }
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                if (log.isDebugEnabled) {
                    log.debug("GetStringUTFRegion string={}, value={}, start={}, length={}, buf{}, lr={}", string, value, start, length, buf, context.getLRPointer())
                }
                val data = Arrays.copyOfRange(bytes, start, start + length + 1)
                buf.write(0L, data, 0, data.size)
                return VM.JNI_OK.toLong()
            }
        })

        val _GetPrimitiveArrayCritical: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val isCopy = context.getPointerArg(2)
                val array = getObject<DvmObject<*>>(obj.toIntPeer()) as PrimitiveArray<*>
                if (log.isDebugEnabled) {
                    log.debug("GetPrimitiveArrayCritical array={}, isCopy={}", array, isCopy)
                }
                return Objects.requireNonNull(array)._GetArrayCritical(emulator, isCopy).peer
            }
        })

        val _ReleasePrimitiveArrayCritical: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)!!
                val pointer = context.getPointerArg(2)
                val mode = context.getIntArg(3)
                val array = getObject<DvmObject<*>>(obj.toIntPeer()) as PrimitiveArray<*>
                if (log.isDebugEnabled) {
                    log.debug("ReleasePrimitiveArrayCritical array={}, pointer={}, mode={}", array, pointer, mode)
                }
                Objects.requireNonNull(array)._ReleaseArrayCritical(pointer!!, mode)
                return 0
            }
        })

        val _GetStringCritical: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ReleaseStringCritical: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _NewWeakGlobalRef: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)
                if (obj == null) {
                    return 0
                }
                val dvmObject = Objects.requireNonNull(getObject<DvmObject<*>>(obj.toIntPeer()))
                if (log.isDebugEnabled) {
                    log.debug("NewWeakGlobalRef object={}, dvmObject={}, class={}", obj, dvmObject, dvmObject.javaClass)
                }
                return addObject(dvmObject, true, true).toLong()
            }
        })

        val _DeleteWeakGlobalRef: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _ExceptionCheck: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                if (log.isDebugEnabled) {
                    log.debug("ExceptionCheck throwable={}", throwable)
                }
                return if (throwable == null) VM.JNI_FALSE.toLong() else VM.JNI_TRUE.toLong()
            }
        })

        val _NewDirectByteBuffer: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetDirectBufferAddress: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetDirectBufferCapacity: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _GetObjectRefType: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val obj = context.getPointerArg(1)
                if (obj == null) {
                    return VM.JNIInvalidRefType.toLong()
                }
                val hash = obj.toIntPeer()
                val dvmLocalObject = localObjectMap.get(obj.toIntPeer())
                val dvmGlobalObject: ObjRef?
                if (globalObjectMap.containsKey(hash)) {
                    dvmGlobalObject = globalObjectMap.get(hash)
                } else {
                    dvmGlobalObject = weakGlobalObjectMap.getOrDefault(hash, null)
                }
                if (log.isDebugEnabled) {
                    log.debug("GetObjectRefType object={}, dvmGlobalObject={}, dvmLocalObject={}", obj, dvmGlobalObject, dvmLocalObject)
                }
                if (dvmGlobalObject != null) {
                    return if (dvmGlobalObject.weak) VM.JNIWeakGlobalRefType.toLong() else VM.JNIGlobalRefType.toLong()
                } else if (dvmLocalObject != null) {
                    return VM.JNILocalRefType.toLong()
                } else {
                    return VM.JNIInvalidRefType.toLong()
                }
            }
        })

        val _GetModule: Pointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val last = 0x748
        val impl: VortexdbgPointer = svcMemory.allocate(last + 8, "JNIEnv.impl")
        run {
            var i = 0
            while (i <= last) {
                impl.setLong(i.toLong(), i.toLong())
                i += 8
            }
        }
        impl.setPointer(0x20L, _GetVersion)
        impl.setPointer(0x28L, _DefineClass)
        impl.setPointer(0x30L, _FindClass)
        impl.setPointer(0x38L, _FromReflectedMethod)
        impl.setPointer(0x40L, _FromReflectedField)
        impl.setPointer(0x48L, _ToReflectedMethod)
        impl.setPointer(0x50L, _GetSuperclass)
        impl.setPointer(0x58L, _IsAssignableFrom)
        impl.setPointer(0x60L, _ToReflectedField)
        impl.setPointer(0x68L, _Throw)
        impl.setPointer(0x70L, _ThrowNew)
        impl.setPointer(0x78L, _ExceptionOccurred)
        impl.setPointer(0x80L, _ExceptionDescribe)
        impl.setPointer(0x88L, _ExceptionClear)
        impl.setPointer(0x90L, _FatalError)
        impl.setPointer(0x98L, _PushLocalFrame)
        impl.setPointer(0xa0L, _PopLocalFrame)
        impl.setPointer(0xa8L, _NewGlobalRef)
        impl.setPointer(0xb0L, _DeleteGlobalRef)
        impl.setPointer(0xb8L, _DeleteLocalRef)
        impl.setPointer(0xc0L, _IsSameObject)
        impl.setPointer(0xc8L, _NewLocalRef)
        impl.setPointer(0xd0L, _EnsureLocalCapacity)
        impl.setPointer(0xd8L, _AllocObject)
        impl.setPointer(0xe0L, _NewObject)
        impl.setPointer(0xe8L, _NewObjectV)
        impl.setPointer(0xf0L, _NewObjectA)
        impl.setPointer(0xf8L, _GetObjectClass)
        impl.setPointer(0x100L, _IsInstanceOf)
        impl.setPointer(0x108L, _GetMethodID)
        impl.setPointer(0x110L, _CallObjectMethod)
        impl.setPointer(0x118L, _CallObjectMethodV)
        impl.setPointer(0x120L, _CallObjectMethodA)
        impl.setPointer(0x128L, _CallBooleanMethod)
        impl.setPointer(0x130L, _CallBooleanMethodV)
        impl.setPointer(0x138L, _CallBooleanMethodA)
        impl.setPointer(0x140L, _CallByteMethod)
        impl.setPointer(0x148L, _CallByteMethodV)
        impl.setPointer(0x150L, _CallByteMethodA)
        impl.setPointer(0x158L, _CallCharMethod)
        impl.setPointer(0x160L, _CallCharMethodV)
        impl.setPointer(0x168L, _CallCharMethodA)
        impl.setPointer(0x170L, _CallShortMethod)
        impl.setPointer(0x178L, _CallShortMethodV)
        impl.setPointer(0x180L, _CallShortMethodA)
        impl.setPointer(0x188L, _CallIntMethod)
        impl.setPointer(0x190L, _CallIntMethodV)
        impl.setPointer(0x198L, _CallIntMethodA)
        impl.setPointer(0x1a0L, _CallLongMethod)
        impl.setPointer(0x1a8L, _CallLongMethodV)
        impl.setPointer(0x1b0L, _CallLongMethodA)
        impl.setPointer(0x1b8L, _CallFloatMethod)
        impl.setPointer(0x1c0L, _CallFloatMethodV)
        impl.setPointer(0x1c8L, _CallFloatMethodA)
        impl.setPointer(0x1d0L, _CallDoubleMethod)
        impl.setPointer(0x1d8L, _CallDoubleMethodV)
        impl.setPointer(0x1e0L, _CallDoubleMethodA)
        impl.setPointer(0x1e8L, _CallVoidMethod)
        impl.setPointer(0x1f0L, _CallVoidMethodV)
        impl.setPointer(0x1f8L, _CallVoidMethodA)
        impl.setPointer(0x200L, _CallNonvirtualObjectMethod)
        impl.setPointer(0x208L, _CallNonvirtualObjectMethodV)
        impl.setPointer(0x210L, _CallNonvirtualObjectMethodA)
        impl.setPointer(0x218L, _CallNonvirtualBooleanMethod)
        impl.setPointer(0x220L, _CallNonvirtualBooleanMethodV)
        impl.setPointer(0x228L, _CallNonvirtualBooleanMethodA)
        impl.setPointer(0x230L, _CallNonvirtualByteMethod)
        impl.setPointer(0x238L, _CallNonvirtualByteMethodV)
        impl.setPointer(0x240L, _CallNonvirtualByteMethodA)
        impl.setPointer(0x248L, _CallNonvirtualCharMethod)
        impl.setPointer(0x250L, _CallNonvirtualCharMethodV)
        impl.setPointer(0x258L, _CallNonvirtualCharMethodA)
        impl.setPointer(0x260L, _CallNonvirtualShortMethod)
        impl.setPointer(0x268L, _CallNonvirtualShortMethodV)
        impl.setPointer(0x270L, _CallNonvirtualShortMethodA)
        impl.setPointer(0x278L, _CallNonvirtualIntMethod)
        impl.setPointer(0x280L, _CallNonvirtualIntMethodV)
        impl.setPointer(0x288L, _CallNonvirtualIntMethodA)
        impl.setPointer(0x290L, _CallNonvirtualLongMethod)
        impl.setPointer(0x298L, _CallNonvirtualLongMethodV)
        impl.setPointer(0x2a0L, _CallNonvirtualLongMethodA)
        impl.setPointer(0x2a8L, _CallNonvirtualFloatMethod)
        impl.setPointer(0x2b0L, _CallNonvirtualFloatMethodV)
        impl.setPointer(0x2b8L, _CallNonvirtualFloatMethodA)
        impl.setPointer(0x2c0L, _CallNonvirtualDoubleMethod)
        impl.setPointer(0x2c8L, _CallNonvirtualDoubleMethodV)
        impl.setPointer(0x2d0L, _CallNonvirtualDoubleMethodA)
        impl.setPointer(0x2d8L, _CallNonvirtualVoidMethod)
        impl.setPointer(0x2e0L, _CallNonvirtualVoidMethodV)
        impl.setPointer(0x2e8L, _CallNonVirtualVoidMethodA)
        impl.setPointer(0x2f0L, _GetFieldID)
        impl.setPointer(0x2f8L, _GetObjectField)
        impl.setPointer(0x300L, _GetBooleanField)
        impl.setPointer(0x308L, _GetByteField)
        impl.setPointer(0x310L, _GetCharField)
        impl.setPointer(0x318L, _GetShortField)
        impl.setPointer(0x320L, _GetIntField)
        impl.setPointer(0x328L, _GetLongField)
        impl.setPointer(0x330L, _GetFloatField)
        impl.setPointer(0x338L, _GetDoubleField)
        impl.setPointer(0x340L, _SetObjectField)
        impl.setPointer(0x348L, _SetBooleanField)
        impl.setPointer(0x350L, _SetByteField)
        impl.setPointer(0x358L, _SetCharField)
        impl.setPointer(0x360L, _SetShortField)
        impl.setPointer(0x368L, _SetIntField)
        impl.setPointer(0x370L, _SetLongField)
        impl.setPointer(0x378L, _SetFloatField)
        impl.setPointer(0x380L, _SetDoubleField)
        impl.setPointer(0x388L, _GetStaticMethodID)
        impl.setPointer(0x390L, _CallStaticObjectMethod)
        impl.setPointer(0x398L, _CallStaticObjectMethodV)
        impl.setPointer(0x3a0L, _CallStaticObjectMethodA)
        impl.setPointer(0x3a8L, _CallStaticBooleanMethod)
        impl.setPointer(0x3b0L, _CallStaticBooleanMethodV)
        impl.setPointer(0x3b8L, _CallStaticBooleanMethodA)
        impl.setPointer(0x3c0L, _CallStaticByteMethod)
        impl.setPointer(0x3c8L, _CallStaticByteMethodV)
        impl.setPointer(0x3d0L, _CallStaticByteMethodA)
        impl.setPointer(0x3d8L, _CallStaticCharMethod)
        impl.setPointer(0x3e0L, _CallStaticCharMethodV)
        impl.setPointer(0x3e8L, _CallStaticCharMethodA)
        impl.setPointer(0x3f0L, _CallStaticShortMethod)
        impl.setPointer(0x3f8L, _CallStaticShortMethodV)
        impl.setPointer(0x400L, _CallStaticShortMethodA)
        impl.setPointer(0x408L, _CallStaticIntMethod)
        impl.setPointer(0x410L, _CallStaticIntMethodV)
        impl.setPointer(0x418L, _CallStaticIntMethodA)
        impl.setPointer(0x420L, _CallStaticLongMethod)
        impl.setPointer(0x428L, _CallStaticLongMethodV)
        impl.setPointer(0x430L, _CallStaticLongMethodA)
        impl.setPointer(0x438L, _CallStaticFloatMethod)
        impl.setPointer(0x440L, _CallStaticFloatMethodV)
        impl.setPointer(0x448L, _CallStaticFloatMethodA)
        impl.setPointer(0x450L, _CallStaticDoubleMethod)
        impl.setPointer(0x458L, _CallStaticDoubleMethodV)
        impl.setPointer(0x460L, _CallStaticDoubleMethodA)
        impl.setPointer(0x468L, _CallStaticVoidMethod)
        impl.setPointer(0x470L, _CallStaticVoidMethodV)
        impl.setPointer(0x478L, _CallStaticVoidMethodA)
        impl.setPointer(0x480L, _GetStaticFieldID)
        impl.setPointer(0x488L, _GetStaticObjectField)
        impl.setPointer(0x490L, _GetStaticBooleanField)
        impl.setPointer(0x498L, _GetStaticByteField)
        impl.setPointer(0x4a0L, _GetStaticCharField)
        impl.setPointer(0x4a8L, _GetStaticShortField)
        impl.setPointer(0x4b0L, _GetStaticIntField)
        impl.setPointer(0x4b8L, _GetStaticLongField)
        impl.setPointer(0x4c0L, _GetStaticFloatField)
        impl.setPointer(0x4c8L, _GetStaticDoubleField)
        impl.setPointer(0x4d0L, _SetStaticObjectField)
        impl.setPointer(0x4d8L, _SetStaticBooleanField)
        impl.setPointer(0x4e0L, _SetStaticByteField)
        impl.setPointer(0x4e8L, _SetStaticCharField)
        impl.setPointer(0x4f0L, _SetStaticShortField)
        impl.setPointer(0x4f8L, _SetStaticIntField)
        impl.setPointer(0x500L, _SetStaticLongField)
        impl.setPointer(0x508L, _SetStaticFloatField)
        impl.setPointer(0x510L, _SetStaticDoubleField)
        impl.setPointer(0x518L, _NewString)
        impl.setPointer(0x520L, _GetStringLength)
        impl.setPointer(0x528L, _GetStringChars)
        impl.setPointer(0x530L, _ReleaseStringChars)
        impl.setPointer(0x538L, _NewStringUTF)
        impl.setPointer(0x540L, _GetStringUTFLength)
        impl.setPointer(0x548L, _GetStringUTFChars)
        impl.setPointer(0x550L, _ReleaseStringUTFChars)
        impl.setPointer(0x558L, _GetArrayLength)
        impl.setPointer(0x560L, _NewObjectArray)
        impl.setPointer(0x568L, _GetObjectArrayElement)
        impl.setPointer(0x570L, _SetObjectArrayElement)
        impl.setPointer(0x578L, _NewBooleanArray)
        impl.setPointer(0x580L, _NewByteArray)
        impl.setPointer(0x588L, _NewCharArray)
        impl.setPointer(0x590L, _NewShortArray)
        impl.setPointer(0x598L, _NewIntArray)
        impl.setPointer(0x5a0L, _NewLongArray)
        impl.setPointer(0x5a8L, _NewFloatArray)
        impl.setPointer(0x5b0L, _NewDoubleArray)
        impl.setPointer(0x5b8L, _GetBooleanArrayElements)
        impl.setPointer(0x5c0L, _GetByteArrayElements)
        impl.setPointer(0x5c8L, _GetCharArrayElements)
        impl.setPointer(0x5d0L, _GetShortArrayElements)
        impl.setPointer(0x5d8L, _GetIntArrayElements)
        impl.setPointer(0x5e0L, _GetLongArrayElements)
        impl.setPointer(0x5e8L, _GetFloatArrayElements)
        impl.setPointer(0x5f0L, _GetDoubleArrayElements)
        impl.setPointer(0x5f8L, _ReleaseBooleanArrayElements)
        impl.setPointer(0x600L, _ReleaseByteArrayElements)
        impl.setPointer(0x608L, _ReleaseCharArrayElements)
        impl.setPointer(0x610L, _ReleaseShortArrayElements)
        impl.setPointer(0x618L, _ReleaseIntArrayElements)
        impl.setPointer(0x620L, _ReleaseLongArrayElements)
        impl.setPointer(0x628L, _ReleaseFloatArrayElements)
        impl.setPointer(0x630L, _ReleaseDoubleArrayElements)
        impl.setPointer(0x638L, _GetBooleanArrayRegion)
        impl.setPointer(0x640L, _GetByteArrayRegion)
        impl.setPointer(0x648L, _GetCharArrayRegion)
        impl.setPointer(0x650L, _GetShortArrayRegion)
        impl.setPointer(0x658L, _GetIntArrayRegion)
        impl.setPointer(0x660L, _GetLongArrayRegion)
        impl.setPointer(0x668L, _GetFloatArrayRegion)
        impl.setPointer(0x670L, _GetDoubleArrayRegion)
        impl.setPointer(0x678L, _SetBooleanArrayRegion)
        impl.setPointer(0x680L, _SetByteArrayRegion)
        impl.setPointer(0x688L, _SetCharArrayRegion)
        impl.setPointer(0x690L, _SetShortArrayRegion)
        impl.setPointer(0x698L, _SetIntArrayRegion)
        impl.setPointer(0x6a0L, _SetLongArrayRegion)
        impl.setPointer(0x6a8L, _SetFloatArrayRegion)
        impl.setPointer(0x6b0L, _SetDoubleArrayRegion)
        impl.setPointer(0x6b8L, _RegisterNatives)
        impl.setPointer(0x6c0L, _UnregisterNatives)
        impl.setPointer(0x6c8L, _MonitorEnter)
        impl.setPointer(0x6d0L, _MonitorExit)
        impl.setPointer(0x6d8L, _GetJavaVM)
        impl.setPointer(0x6e0L, _GetStringRegion)
        impl.setPointer(0x6e8L, _GetStringUTFRegion)
        impl.setPointer(0x6f0L, _GetPrimitiveArrayCritical)
        impl.setPointer(0x6f8L, _ReleasePrimitiveArrayCritical)
        impl.setPointer(0x700L, _GetStringCritical)
        impl.setPointer(0x708L, _ReleaseStringCritical)
        impl.setPointer(0x710L, _NewWeakGlobalRef)
        impl.setPointer(0x718L, _DeleteWeakGlobalRef)
        impl.setPointer(0x720L, _ExceptionCheck)
        impl.setPointer(0x728L, _NewDirectByteBuffer)
        impl.setPointer(0x730L, _GetDirectBufferAddress)
        impl.setPointer(0x738L, _GetDirectBufferCapacity)
        impl.setPointer(0x740L, _GetObjectRefType)
        impl.setPointer(last.toLong(), _GetModule)

        _JNIEnv = svcMemory.allocate(emulator.getPointerSize(), "_JNIEnv")
        _JNIEnv.setPointer(0L, impl)

        val _DestroyJavaVM: VortexdbgPointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })
        val _AttachCurrentThread: VortexdbgPointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val vm = context.getPointerArg(0)
                val env = context.getPointerArg(1)!!
                val args = context.getPointerArg(2) // JavaVMAttachArgs*
                log.debug("AttachCurrentThread vm={}, env={}, args={}", vm, env.getPointer(0L), args)
                env.setPointer(0L, _JNIEnv)
                return VM.JNI_OK.toLong()
            }
        })
        val _DetachCurrentThread: VortexdbgPointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val vm = context.getPointerArg(0)
                log.debug("DetachCurrentThread vm={}", vm)
                return 0L
            }
        })

        val _GetEnv: VortexdbgPointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                val context = emulator.getContext<RegisterContext>()
                val vm = context.getPointerArg(0)
                val env = context.getPointerArg(1)!!
                val version = context.getIntArg(2)
                if (log.isDebugEnabled) {
                    log.debug("GetEnv vm={}, env={}, version=0x{}", vm, env.getPointer(0L), Integer.toHexString(version))
                }
                env.setPointer(0L, _JNIEnv)
                return VM.JNI_OK.toLong()
            }
        })
        val _AttachCurrentThreadAsDaemon: VortexdbgPointer = svcMemory.registerSvc(object : Arm64Svc() {
            override fun handle(emulator: Emulator<*>): Long {
                throw UnsupportedOperationException()
            }
        })

        val _JNIInvokeInterface: VortexdbgPointer = svcMemory.allocate(emulator.getPointerSize() * 8, "_JNIInvokeInterface")
        run {
            var i = 0
            while (i < emulator.getPointerSize() * 8) {
                _JNIInvokeInterface.setLong(i.toLong(), i.toLong())
                i += emulator.getPointerSize()
            }
        }
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 3L, _DestroyJavaVM)
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 4L, _AttachCurrentThread)
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 5L, _DetachCurrentThread)
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 6L, _GetEnv)
        _JNIInvokeInterface.setPointer(emulator.getPointerSize() * 7L, _AttachCurrentThreadAsDaemon)

        _JavaVM.setPointer(0L, _JNIInvokeInterface)

        if (log.isDebugEnabled) {
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
        val soData = apk.getFileData("lib/arm64-v8a/$soName")
        if (soData != null) {
            if (log.isDebugEnabled) {
                log.debug("resolve arm64-v8a library: {}", soName)
            }
            return soData
        } else {
            return null
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(DalvikVM64::class.java)
    }
}
