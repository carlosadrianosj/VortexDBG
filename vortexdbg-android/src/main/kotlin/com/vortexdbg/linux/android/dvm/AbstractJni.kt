package com.vortexdbg.linux.android.dvm

import com.vortexdbg.linux.android.dvm.api.ApplicationInfo
import com.vortexdbg.linux.android.dvm.api.AssetManager
import com.vortexdbg.linux.android.dvm.api.Binder
import com.vortexdbg.linux.android.dvm.api.Bundle
import com.vortexdbg.linux.android.dvm.api.ClassLoader
import com.vortexdbg.linux.android.dvm.api.PackageInfo
import com.vortexdbg.linux.android.dvm.api.ServiceManager
import com.vortexdbg.linux.android.dvm.api.Signature
import com.vortexdbg.linux.android.dvm.api.SystemService
import com.vortexdbg.linux.android.dvm.array.ArrayObject
import com.vortexdbg.linux.android.dvm.array.ByteArray
import com.vortexdbg.linux.android.dvm.wrapper.DvmBoolean
import com.vortexdbg.linux.android.dvm.wrapper.DvmInteger
import com.vortexdbg.linux.android.dvm.wrapper.DvmLong
import net.dongliu.apk.parser.bean.CertificateMeta
import org.slf4j.LoggerFactory

import javax.crypto.Cipher
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.SecretKeySpec
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.security.InvalidKeyException
import java.security.Key
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.util.Collections
import java.util.HashMap
import java.util.Iterator
import java.util.Locale
import java.util.UUID

abstract class AbstractJni : Jni {

    override fun getStaticObjectField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): DvmObject<*> {
        return getStaticObjectField(vm, dvmClass, dvmField.getSignature())
    }

    override fun getStaticObjectField(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*> {
        when (signature) {
            "android/content/Context->TELEPHONY_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.TELEPHONY_SERVICE)
            "android/content/Context->WIFI_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.WIFI_SERVICE)
            "android/content/Context->CONNECTIVITY_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.CONNECTIVITY_SERVICE)
            "android/content/Context->ACCESSIBILITY_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.ACCESSIBILITY_SERVICE)
            "android/content/Context->KEYGUARD_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.KEYGUARD_SERVICE)
            "android/content/Context->ACTIVITY_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.ACTIVITY_SERVICE)
            "android/content/Context->LOCATION_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.LOCATION_SERVICE)
            "android/content/Context->WINDOW_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.WINDOW_SERVICE)
            "android/content/Context->SENSOR_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.SENSOR_SERVICE)
            "android/content/Context->UI_MODE_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.UI_MODE_SERVICE)
            "android/content/Context->DISPLAY_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.DISPLAY_SERVICE)
            "android/content/Context->AUDIO_SERVICE:Ljava/lang/String;" -> return StringObject(vm, SystemService.AUDIO_SERVICE)
            "java/lang/Void->TYPE:Ljava/lang/Class;" -> return vm.resolveClass("java/lang/Void")
            "java/lang/Boolean->TYPE:Ljava/lang/Class;" -> return vm.resolveClass("java/lang/Boolean")
            "java/lang/Byte->TYPE:Ljava/lang/Class;" -> return vm.resolveClass("java/lang/Byte")
            "java/lang/Character->TYPE:Ljava/lang/Class;" -> return vm.resolveClass("java/lang/Character")
            "java/lang/Short->TYPE:Ljava/lang/Class;" -> return vm.resolveClass("java/lang/Short")
            "java/lang/Integer->TYPE:Ljava/lang/Class;" -> return vm.resolveClass("java/lang/Integer")
            "java/lang/Long->TYPE:Ljava/lang/Class;" -> return vm.resolveClass("java/lang/Long")
            "java/lang/Float->TYPE:Ljava/lang/Class;" -> return vm.resolveClass("java/lang/Float")
            "java/lang/Double->TYPE:Ljava/lang/Class;" -> return vm.resolveClass("java/lang/Double")
        }

        throw UnsupportedOperationException(signature)
    }

    override fun getStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Boolean {
        return getStaticBooleanField(vm, dvmClass, dvmField.getSignature())
    }

    override fun getStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, signature: String): Boolean {
        throw UnsupportedOperationException(signature)
    }

    override fun getStaticByteField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Byte {
        return getStaticByteField(vm, dvmClass, dvmField.getSignature())
    }

    override fun getStaticByteField(vm: BaseVM, dvmClass: DvmClass, signature: String): Byte {
        throw UnsupportedOperationException(signature)
    }

    override fun getStaticIntField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Int {
        return getStaticIntField(vm, dvmClass, dvmField.getSignature())
    }

    override fun getStaticIntField(vm: BaseVM, dvmClass: DvmClass, signature: String): Int {
        if ("android/content/pm/PackageManager->GET_SIGNATURES:I" == signature) {
            return 0x40
        }
        throw UnsupportedOperationException(signature)
    }

    override fun getStaticDoubleField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Double {
        return getStaticDoubleField(vm, dvmClass, dvmField.getSignature())
    }

    override fun getStaticDoubleField(vm: BaseVM, dvmClass: DvmClass, signature: String): Double {
        throw UnsupportedOperationException(signature)
    }

    override fun getObjectField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): DvmObject<*> {
        return getObjectField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getObjectField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): DvmObject<*> {
        if ("android/content/pm/PackageInfo->signatures:[Landroid/content/pm/Signature;" == signature &&
            dvmObject is PackageInfo) {
            val packageInfo = dvmObject
            if (packageInfo.getPackageName() == vm.getPackageName()) {
                val metas: kotlin.Array<CertificateMeta>? = vm.getSignatures()
                if (metas != null) {
                    val signatures = arrayOfNulls<DvmObject<*>?>(metas.size)
                    for (i in metas.indices) {
                        signatures[i] = Signature(vm, metas[i])
                    }
                    return ArrayObject(*signatures)
                }
            }
        }
        if ("android/content/pm/PackageInfo->versionName:Ljava/lang/String;" == signature &&
            dvmObject is PackageInfo) {
            val packageInfo = dvmObject
            if (packageInfo.getPackageName() == vm.getPackageName()) {
                val versionName = vm.getVersionName()
                if (versionName != null) {
                    return StringObject(vm, versionName)
                }
            }
        }

        throw UnsupportedOperationException(signature)
    }

    override fun callStaticBooleanMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Boolean {
        return callStaticBooleanMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticBooleanMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Boolean {
        throw UnsupportedOperationException(signature)
    }

    override fun callStaticBooleanMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Boolean {
        return callStaticBooleanMethodV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun callStaticBooleanMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): Boolean {
        throw UnsupportedOperationException(signature)
    }

    override fun callStaticIntMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Int {
        return callStaticIntMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticIntMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Int {
        throw UnsupportedOperationException(signature)
    }

    override fun callStaticIntMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Int {
        return callStaticIntMethodV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun callStaticIntMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): Int {
        throw UnsupportedOperationException(signature)
    }

    override fun callLongMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Long {
        return callLongMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callLongMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Long {
        if ("java/lang/Long->longValue()J" == signature) {
            val value = dvmObject as DvmLong
            return value.getValue()
        }

        throw UnsupportedOperationException(signature)
    }

    override fun callLongMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Long {
        return callLongMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callLongMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Long {
        throw UnsupportedOperationException(signature)
    }

    override fun callCharMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Char {
        return callCharMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callCharMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Char {
        throw UnsupportedOperationException(signature)
    }

    override fun callFloatMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Float {
        return callFloatMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callFloatMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Float {
        throw UnsupportedOperationException(signature)
    }

    override fun callDoubleMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Double {
        return callDoubleMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callDoubleMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Double {
        throw UnsupportedOperationException(signature)
    }

    override fun callObjectMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*> {
        return callObjectMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callObjectMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): DvmObject<*> {
        when (signature) {
            "android/app/Application->getAssets()Landroid/content/res/AssetManager;" -> return AssetManager(vm, signature)
            "android/app/Application->getClassLoader()Ljava/lang/ClassLoader;" -> return ClassLoader(vm, signature)
            "android/app/Application->getContentResolver()Landroid/content/ContentResolver;" -> return vm.resolveClass("android/content/ContentResolver").newObject(signature)
            "java/util/ArrayList->get(I)Ljava/lang/Object;" -> {
                val index = vaList.getIntArg(0)
                val arrayList = dvmObject as ArrayListObject
                return arrayList.getValue().get(index)
            }
            "android/app/Application->getSystemService(Ljava/lang/String;)Ljava/lang/Object;" -> {
                val serviceName = vaList.getObjectArg<StringObject>(0)
                assert(serviceName != null)
                return SystemService(vm, serviceName!!.getValue())
            }
            "java/lang/String->toString()Ljava/lang/String;" -> return dvmObject
            "java/lang/Class->getName()Ljava/lang/String;" -> return StringObject(vm, (dvmObject as DvmClass).getName())
            "android/view/accessibility/AccessibilityManager->getEnabledAccessibilityServiceList(I)Ljava/util/List;" -> return ArrayListObject(vm, Collections.emptyList())
            "java/util/Enumeration->nextElement()Ljava/lang/Object;" -> return (dvmObject as Enumeration).nextElement()
            "java/util/Locale->getLanguage()Ljava/lang/String;" -> {
                val locale = dvmObject.getValue() as Locale
                return StringObject(vm, locale.language)
            }
            "java/util/Locale->getCountry()Ljava/lang/String;" -> {
                val locale = dvmObject.getValue() as Locale
                return StringObject(vm, locale.country)
            }
            "android/os/IServiceManager->getService(Ljava/lang/String;)Landroid/os/IBinder;" -> {
                val serviceManager = dvmObject as ServiceManager
                val serviceName = vaList.getObjectArg<StringObject>(0)
                assert(serviceName != null)
                return serviceManager.getService(vm, serviceName!!.getValue())
            }
            "java/io/File->getAbsolutePath()Ljava/lang/String;" -> {
                val file = dvmObject.getValue() as File
                return StringObject(vm, file.absolutePath)
            }
            "android/app/Application->getPackageManager()Landroid/content/pm/PackageManager;",
            "android/content/ContextWrapper->getPackageManager()Landroid/content/pm/PackageManager;",
            "android/content/Context->getPackageManager()Landroid/content/pm/PackageManager;" -> {
                val clazz = vm.resolveClass("android/content/pm/PackageManager")
                return clazz.newObject(signature)
            }
            "android/content/pm/PackageManager->getPackageInfo(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;" -> {
                val packageName = vaList.getObjectArg<StringObject>(0)
                assert(packageName != null)
                val flags = vaList.getIntArg(1)
                if (log.isDebugEnabled) {
                    log.debug("callObjectMethodV getPackageInfo packageName={}, flags=0x{}", packageName!!.getValue(), Integer.toHexString(flags))
                }
                return PackageInfo(vm, packageName!!.getValue(), flags)
            }
            "android/app/Application->getPackageName()Ljava/lang/String;",
            "android/content/ContextWrapper->getPackageName()Ljava/lang/String;",
            "android/content/Context->getPackageName()Ljava/lang/String;" -> {
                val packageName = vm.getPackageName()
                if (packageName != null) {
                    return StringObject(vm, packageName)
                }
            }
            "android/content/pm/Signature->toByteArray()[B" -> {
                if (dvmObject is Signature) {
                    val sig = dvmObject
                    return ByteArray(vm, sig.toByteArray())
                }
            }
            "android/content/pm/Signature->toCharsString()Ljava/lang/String;" -> {
                if (dvmObject is Signature) {
                    val sig = dvmObject
                    return StringObject(vm, sig.toCharsString())
                }
            }
            "java/lang/String->getBytes()[B" -> {
                val str = dvmObject.getValue() as String
                return ByteArray(vm, str.toByteArray())
            }
            "java/lang/String->getBytes(Ljava/lang/String;)[B" -> {
                val str = dvmObject.getValue() as String
                val charsetName = vaList.getObjectArg<StringObject>(0)
                assert(charsetName != null)
                try {
                    return ByteArray(vm, str.toByteArray(charset(charsetName!!.getValue())))
                } catch (e: UnsupportedEncodingException) {
                    throw IllegalStateException(e)
                }
            }
            "java/security/cert/CertificateFactory->generateCertificate(Ljava/io/InputStream;)Ljava/security/cert/Certificate;" -> {
                val factory = dvmObject.getValue() as CertificateFactory
                val stream = vaList.getObjectArg<DvmObject<*>>(0)
                assert(stream != null)
                val inputStream = stream!!.getValue() as InputStream
                try {
                    return vm.resolveClass("java/security/cert/Certificate").newObject(factory.generateCertificate(inputStream))
                } catch (e: CertificateException) {
                    throw IllegalStateException(e)
                }
            }
            "java/security/cert/Certificate->getEncoded()[B" -> {
                val certificate = dvmObject.getValue() as Certificate
                try {
                    return ByteArray(vm, certificate.encoded)
                } catch (e: CertificateEncodingException) {
                    throw IllegalStateException(e)
                }
            }
            "java/security/MessageDigest->digest([B)[B" -> {
                val messageDigest = dvmObject.getValue() as MessageDigest
                val array = vaList.getObjectArg<ByteArray>(0)
                assert(array != null)
                return ByteArray(vm, messageDigest.digest(array!!.getValue()))
            }
            "java/util/ArrayList->remove(I)Ljava/lang/Object;" -> {
                val index = vaList.getIntArg(0)
                val list = dvmObject as ArrayListObject
                @Suppress("UNCHECKED_CAST")
                return (list.getValue() as MutableList<DvmObject<*>>).removeAt(index)
            }
            "java/util/List->get(I)Ljava/lang/Object;" -> {
                val list = dvmObject.getValue() as List<*>
                return list.get(vaList.getIntArg(0)) as DvmObject<*>
            }
            "java/util/Map->entrySet()Ljava/util/Set;" -> {
                val map = dvmObject.getValue() as Map<*, *>
                return vm.resolveClass("java/util/Set").newObject(map.entries)
            }
            "java/util/Set->iterator()Ljava/util/Iterator;" -> {
                val set = dvmObject.getValue() as Set<*>
                return vm.resolveClass("java/util/Iterator").newObject(set.iterator())
            }
            "java/util/UUID->toString()Ljava/lang/String;" -> {
                val uuid = dvmObject.getValue() as UUID
                return StringObject(vm, uuid.toString())
            }
            "java/lang/CharSequence->toString()Ljava/lang/String;" -> {
                return StringObject(vm, dvmObject.getValue().toString())
            }
            "java/lang/String->toLowerCase()Ljava/lang/String;" -> {
                return StringObject(vm, dvmObject.getValue().toString().lowercase())
            }
            "android/content/pm/PackageManager->getApplicationInfo(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;" -> {
                val packageName = vaList.getObjectArg<StringObject>(0)
                if (packageName!!.getValue() == vm.getPackageName()) {
                    return ApplicationInfo(vm)
                } else {
                    throw UnsupportedOperationException(signature)
                }
            }
            "java/lang/String->trim()Ljava/lang/String;" -> {
                val stringObject = dvmObject as StringObject
                return StringObject(vm, stringObject.getValue().trim())
            }
        }

        throw UnsupportedOperationException(signature)
    }

    override fun callStaticObjectMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
        return callStaticObjectMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticObjectMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): DvmObject<*> {
        if ("android/app/ActivityThread->currentPackageName()Ljava/lang/String;" == signature) {
            val packageName = vm.getPackageName()
            if (packageName != null) {
                return StringObject(vm, packageName)
            }
        }
        throw UnsupportedOperationException(signature)
    }

    override fun callStaticObjectMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*> {
        return callStaticObjectMethodV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun callStaticObjectMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): DvmObject<*> {
        when (signature) {
            "com/android/internal/os/BinderInternal->getContextObject()Landroid/os/IBinder;" -> return Binder(vm, signature)
            "android/app/ActivityThread->currentActivityThread()Landroid/app/ActivityThread;" -> return dvmClass.newObject(null)
            "android/app/ActivityThread->currentApplication()Landroid/app/Application;" -> return vm.resolveClass("android/app/Application", vm.resolveClass("android/content/ContextWrapper", vm.resolveClass("android/content/Context"))).newObject(signature)
            "java/util/Locale->getDefault()Ljava/util/Locale;" -> return dvmClass.newObject(Locale.getDefault())
            "android/os/ServiceManagerNative->asInterface(Landroid/os/IBinder;)Landroid/os/IServiceManager;" -> return ServiceManager(vm, signature)
            "com/android/internal/telephony/ITelephony\$Stub->asInterface(Landroid/os/IBinder;)Lcom/android/internal/telephony/ITelephony;" -> return vaList.getObjectArg<DvmObject<*>>(0)!!
            "java/security/cert/CertificateFactory->getInstance(Ljava/lang/String;)Ljava/security/cert/CertificateFactory;" -> {
                val type = vaList.getObjectArg<StringObject>(0)
                assert(type != null)
                try {
                    return dvmClass.newObject(CertificateFactory.getInstance(type!!.getValue()))
                } catch (e: CertificateException) {
                    throw IllegalStateException(e)
                }
            }
            "java/security/KeyFactory->getInstance(Ljava/lang/String;)Ljava/security/KeyFactory;" -> {
                val algorithm = vaList.getObjectArg<StringObject>(0)
                assert(algorithm != null)
                try {
                    return dvmClass.newObject(KeyFactory.getInstance(algorithm!!.getValue()))
                } catch (e: NoSuchAlgorithmException) {
                    throw IllegalStateException(e)
                }
            }
            "javax/crypto/Cipher->getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;" -> {
                val transformation = vaList.getObjectArg<StringObject>(0)
                assert(transformation != null)
                try {
                    return dvmClass.newObject(Cipher.getInstance(transformation!!.getValue()))
                } catch (e: NoSuchAlgorithmException) {
                    throw IllegalStateException(e)
                } catch (e: NoSuchPaddingException) {
                    throw IllegalStateException(e)
                }
            }
            "java/security/MessageDigest->getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;" -> {
                val type = vaList.getObjectArg<StringObject>(0)
                assert(type != null)
                try {
                    return dvmClass.newObject(MessageDigest.getInstance(type!!.getValue()))
                } catch (e: NoSuchAlgorithmException) {
                    throw IllegalStateException(e)
                }
            }
            "java/util/UUID->randomUUID()Ljava/util/UUID;" -> {
                return dvmClass.newObject(UUID.randomUUID())
            }
            "android/app/ActivityThread->currentPackageName()Ljava/lang/String;" -> {
                val packageName = vm.getPackageName()
                if (packageName != null) {
                    return StringObject(vm, packageName)
                }
            }
        }

        throw UnsupportedOperationException(signature)
    }

    override fun callByteMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Byte {
        return callByteMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callByteMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Byte {
        throw UnsupportedOperationException(signature)
    }

    override fun callShortMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Short {
        return callShortMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callShortMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Short {
        throw UnsupportedOperationException(signature)
    }

    override fun callIntMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Int {
        return callIntMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callIntMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Int {
        when (signature) {
            "android/os/Bundle->getInt(Ljava/lang/String;)I" -> {
                val bundle = dvmObject as Bundle
                val key = vaList.getObjectArg<StringObject>(0)
                assert(key != null)
                return bundle.getInt(key!!.getValue())
            }
            "java/util/ArrayList->size()I" -> {
                val list = dvmObject as ArrayListObject
                return list.size()
            }
            "android/content/pm/Signature->hashCode()I" -> {
                if (dvmObject is Signature) {
                    val sig = dvmObject
                    return sig.getHashCode()
                }
            }
            "java/lang/Integer->intValue()I" -> {
                val integer = dvmObject as DvmInteger
                return integer.getValue()
            }
            "java/util/List->size()I" -> {
                val list = dvmObject.getValue() as List<*>
                return list.size
            }
            "java/util/Map->size()I" -> {
                val map = dvmObject.getValue() as Map<*, *>
                return map.size
            }
        }

        throw UnsupportedOperationException(signature)
    }

    override fun callStaticLongMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Long {
        return callStaticLongMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticLongMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Long {
        throw UnsupportedOperationException(signature)
    }

    override fun callStaticLongMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): Long {
        return callStaticLongMethodV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun callStaticLongMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): Long {
        throw UnsupportedOperationException(signature)
    }

    override fun callBooleanMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Boolean {
        return callBooleanMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callBooleanMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Boolean {
        if ("java/lang/Boolean->booleanValue()Z" == signature) {
            val dvmBoolean = dvmObject as DvmBoolean
            return dvmBoolean.getValue()
        }

        throw UnsupportedOperationException(signature)
    }

    override fun callBooleanMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList): Boolean {
        return callBooleanMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callBooleanMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList): Boolean {
        when (signature) {
            "java/util/Enumeration->hasMoreElements()Z" -> return (dvmObject as Enumeration).hasMoreElements()
            "java/util/ArrayList->isEmpty()Z" -> return (dvmObject as ArrayListObject).isEmpty()
            "java/util/Iterator->hasNext()Z" -> {
                val iterator = dvmObject.getValue()
                if (iterator is Iterator<*>) {
                    return iterator.hasNext()
                }
                run {
                    val str = dvmObject.getValue() as String
                    val prefix = vaList.getObjectArg<StringObject>(0)
                    return str.startsWith(prefix!!.getValue())
                }
            }
            "java/lang/String->startsWith(Ljava/lang/String;)Z" -> {
                val str = dvmObject.getValue() as String
                val prefix = vaList.getObjectArg<StringObject>(0)
                return str.startsWith(prefix!!.getValue())
            }
        }

        throw UnsupportedOperationException(signature)
    }

    override fun getByteField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Byte {
        return getByteField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getByteField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Byte {
        throw UnsupportedOperationException(signature)
    }

    override fun getIntField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Int {
        return getIntField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getIntField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Int {
        if ("android/content/pm/PackageInfo->versionCode:I" == signature) {
            return vm.getVersionCode().toInt()
        }
        throw UnsupportedOperationException(signature)
    }

    override fun getLongField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Long {
        return getLongField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getLongField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Long {
        throw UnsupportedOperationException(signature)
    }

    override fun getFloatField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Float {
        return getFloatField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getFloatField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Float {
        throw UnsupportedOperationException(signature)
    }

    override fun getDoubleField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Double {
        return getDoubleField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getDoubleField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Double {
        throw UnsupportedOperationException(signature)
    }

    override fun callStaticFloatMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Float {
        return callStaticFloatMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticFloatMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Float {
        throw UnsupportedOperationException(signature)
    }

    override fun callStaticDoubleMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): Double {
        return callStaticDoubleMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticDoubleMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): Double {
        throw UnsupportedOperationException(signature)
    }

    override fun callStaticVoidMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg) {
        callStaticVoidMethod(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun callStaticVoidMethod(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg) {
        throw UnsupportedOperationException(signature)
    }

    override fun callStaticVoidMethodV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList) {
        callStaticVoidMethodV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun callStaticVoidMethodV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList) {
        throw UnsupportedOperationException(signature)
    }

    override fun setObjectField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: DvmObject<*>) {
        setObjectField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setObjectField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: DvmObject<*>) {
        throw UnsupportedOperationException(signature)
    }

    override fun getBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField): Boolean {
        return getBooleanField(vm, dvmObject, dvmField.getSignature())
    }

    override fun getBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String): Boolean {
        throw UnsupportedOperationException(signature)
    }

    override fun newObject(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
        return newObject(vm, dvmClass, dvmMethod.getSignature(), varArg)
    }

    override fun newObject(vm: BaseVM, dvmClass: DvmClass, signature: String, varArg: VarArg): DvmObject<*> {
        when (signature) {
            "java/lang/String-><init>([B)V" -> {
                val array = varArg.getObjectArg<ByteArray>(0)
                return StringObject(vm, String(array!!.getValue()))
            }
            "java/lang/String-><init>([BLjava/lang/String;)V" -> {
                val array = varArg.getObjectArg<ByteArray>(0)
                val string = varArg.getObjectArg<StringObject>(1)
                try {
                    return StringObject(vm, String(array!!.getValue(), charset(string!!.getValue())))
                } catch (e: UnsupportedEncodingException) {
                    throw IllegalStateException(e)
                }
            }
        }

        throw UnsupportedOperationException(signature)
    }

    override fun newObjectV(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod, vaList: VaList): DvmObject<*> {
        return newObjectV(vm, dvmClass, dvmMethod.getSignature(), vaList)
    }

    override fun newObjectV(vm: BaseVM, dvmClass: DvmClass, signature: String, vaList: VaList): DvmObject<*> {
        when (signature) {
            "java/io/ByteArrayInputStream-><init>([B)V" -> {
                val array = vaList.getObjectArg<ByteArray>(0)
                assert(array != null)
                return vm.resolveClass("java/io/ByteArrayInputStream").newObject(ByteArrayInputStream(array!!.getValue()))
            }
            "java/lang/String-><init>([B)V" -> {
                val array = vaList.getObjectArg<ByteArray>(0)
                assert(array != null)
                return StringObject(vm, String(array!!.getValue()))
            }
            "java/lang/String-><init>([BLjava/lang/String;)V" -> {
                val array = vaList.getObjectArg<ByteArray>(0)
                assert(array != null)
                val charsetName = vaList.getObjectArg<StringObject>(1)
                assert(charsetName != null)
                try {
                    return StringObject(vm, String(array!!.getValue(), charset(charsetName!!.getValue())))
                } catch (e: UnsupportedEncodingException) {
                    throw IllegalStateException(e)
                }
            }
            "javax/crypto/spec/SecretKeySpec-><init>([BLjava/lang/String;)V" -> {
                val key = vaList.getObjectArg<DvmObject<*>>(0)!!.getValue() as kotlin.ByteArray
                val algorithm = vaList.getObjectArg<StringObject>(1)
                assert(algorithm != null)
                val secretKeySpec = SecretKeySpec(key, algorithm!!.getValue())
                return dvmClass.newObject(secretKeySpec)
            }
            "java/lang/Integer-><init>(I)V" -> {
                val i = vaList.getIntArg(0)
                return DvmInteger.valueOf(vm, i)
            }
            "java/lang/Boolean-><init>(Z)V" -> {
                val b: Boolean
                b = vaList.getIntArg(0) != 0
                return DvmBoolean.valueOf(vm, b)
            }
        }

        throw UnsupportedOperationException(signature)
    }

    override fun allocObject(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*> {
        if ("java/util/HashMap->allocObject" == signature) {
            return dvmClass.newObject(HashMap<Any?, Any?>())
        }

        throw UnsupportedOperationException(signature)
    }

    override fun setIntField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Int) {
        setIntField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setIntField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Int) {
        throw UnsupportedOperationException(signature)
    }

    override fun setLongField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Long) {
        setLongField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setLongField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Long) {
        throw UnsupportedOperationException(signature)
    }

    override fun setBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Boolean) {
        setBooleanField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setBooleanField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Boolean) {
        throw UnsupportedOperationException(signature)
    }

    override fun setFloatField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Float) {
        setFloatField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setFloatField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Float) {
        throw UnsupportedOperationException(signature)
    }

    override fun setDoubleField(vm: BaseVM, dvmObject: DvmObject<*>, dvmField: DvmField, value: Double) {
        setDoubleField(vm, dvmObject, dvmField.getSignature(), value)
    }

    override fun setDoubleField(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, value: Double) {
        throw UnsupportedOperationException(signature)
    }

    override fun callObjectMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): DvmObject<*> {
        return callObjectMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callObjectMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): DvmObject<*> {
        when (signature) {
            "java/lang/String->getBytes(Ljava/lang/String;)[B" -> {
                val string = dvmObject as StringObject
                val encoding = varArg.getObjectArg<StringObject>(0)
                System.err.println("string=" + string.getValue() + ", encoding=" + encoding!!.getValue())
                try {
                    return ByteArray(vm, string.getValue().toByteArray(charset(encoding.getValue())))
                } catch (e: UnsupportedEncodingException) {
                    throw IllegalStateException(e)
                }
            }
            "android/content/Context->getPackageManager()Landroid/content/pm/PackageManager;",
            "android/app/Activity->getPackageManager()Landroid/content/pm/PackageManager;" -> return vm.resolveClass("android/content/pm/PackageManager").newObject(null)
            "android/content/Context->getApplicationInfo()Landroid/content/pm/ApplicationInfo;",
            "android/app/Activity->getApplicationInfo()Landroid/content/pm/ApplicationInfo;" -> return ApplicationInfo(vm)
            "android/app/Application->getPackageName()Ljava/lang/String;",
            "android/content/ContextWrapper->getPackageName()Ljava/lang/String;",
            "android/app/Activity->getPackageName()Ljava/lang/String;",
            "android/content/Context->getPackageName()Ljava/lang/String;" -> {
                val packageName = vm.getPackageName()
                if (packageName != null) {
                    return StringObject(vm, packageName)
                }
            }
            "android/content/pm/PackageManager->getPackageInfo(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;" -> {
                val packageName = varArg.getObjectArg<StringObject>(0)
                val flags = varArg.getIntArg(1)
                if (log.isDebugEnabled) {
                    log.debug("getPackageInfo packageName={}, flags=0x{}", packageName!!.getValue(), Integer.toHexString(flags))
                }
                return PackageInfo(vm, packageName!!.getValue(), flags)
            }
            "android/content/pm/Signature->toByteArray()[B" -> {
                if (dvmObject is Signature) {
                    val sig = dvmObject
                    return ByteArray(vm, sig.toByteArray())
                }
            }
            "android/content/pm/Signature->toCharsString()Ljava/lang/String;" -> {
                if (dvmObject is Signature) {
                    val sig = dvmObject
                    return StringObject(vm, sig.toCharsString())
                }
            }
            "java/lang/Class->getName()Ljava/lang/String;" -> {
                val clazz = dvmObject as DvmClass
                return StringObject(vm, clazz.getName())
            }
            "java/lang/String->getClass()Ljava/lang/Class;",
            "java/lang/Integer->getClass()Ljava/lang/Class;" -> {
                return dvmObject.getObjectType()!!
            }
            "java/lang/Class->getClassLoader()Ljava/lang/ClassLoader;" -> return ClassLoader(vm, signature)
        }

        throw UnsupportedOperationException(signature)
    }

    override fun callIntMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Int {
        return callIntMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callIntMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Int {
        when (signature) {
            "java/lang/Integer->intValue()I" -> {
                val integer = dvmObject as DvmInteger
                return integer.getValue()
            }
            "java/io/InputStream->read([B)I" -> {
                try {
                    val inputStream = dvmObject.getValue() as java.io.InputStream
                    val array = varArg.getObjectArg<ByteArray>(0)
                    return inputStream.read(array!!.getValue())
                } catch (e: IOException) {
                    throw IllegalStateException(e)
                }
            }
            "android/content/pm/Signature->hashCode()I" -> {
                if (dvmObject is Signature) {
                    val sig = dvmObject
                    return sig.getHashCode()
                }
            }
        }

        throw UnsupportedOperationException(signature)
    }

    override fun callDoubleMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg): Double {
        return callDoubleMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callDoubleMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg): Double {
        throw UnsupportedOperationException(signature)
    }

    override fun callVoidMethod(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, varArg: VarArg) {
        callVoidMethod(vm, dvmObject, dvmMethod.getSignature(), varArg)
    }

    override fun callVoidMethod(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, varArg: VarArg) {
        throw UnsupportedOperationException(signature)
    }

    override fun callVoidMethodV(vm: BaseVM, dvmObject: DvmObject<*>, dvmMethod: DvmMethod, vaList: VaList) {
        callVoidMethodV(vm, dvmObject, dvmMethod.getSignature(), vaList)
    }

    override fun callVoidMethodV(vm: BaseVM, dvmObject: DvmObject<*>, signature: String, vaList: VaList) {
        if ("javax/crypto/Cipher->init(ILjava/security/Key;)V" == signature) {
            val cipher = dvmObject.getValue() as Cipher
            val opmode = vaList.getIntArg(0)
            val key = vaList.getObjectArg<DvmObject<*>>(1)!!.getValue() as Key
            assert(key != null)
            try {
                cipher.init(opmode, key)
            } catch (e: InvalidKeyException) {
                throw IllegalStateException(e)
            }
            return
        }
        throw UnsupportedOperationException(signature)
    }

    override fun setStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Boolean) {
        setStaticBooleanField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticBooleanField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Boolean) {
        throw UnsupportedOperationException(signature)
    }

    override fun setStaticIntField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Int) {
        setStaticIntField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticIntField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Int) {
        throw UnsupportedOperationException(signature)
    }

    override fun setStaticObjectField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: DvmObject<*>) {
        setStaticObjectField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticObjectField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: DvmObject<*>) {
        throw UnsupportedOperationException(signature)
    }

    override fun setStaticLongField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Long) {
        setStaticLongField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticLongField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Long) {
        throw UnsupportedOperationException(signature)
    }

    override fun setStaticFloatField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Float) {
        setStaticFloatField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticFloatField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Float) {
        throw UnsupportedOperationException(signature)
    }

    override fun setStaticDoubleField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField, value: Double) {
        setStaticDoubleField(vm, dvmClass, dvmField.getSignature(), value)
    }

    override fun setStaticDoubleField(vm: BaseVM, dvmClass: DvmClass, signature: String, value: Double) {
        throw UnsupportedOperationException(signature)
    }

    override fun getStaticLongField(vm: BaseVM, dvmClass: DvmClass, dvmField: DvmField): Long {
        return getStaticLongField(vm, dvmClass, dvmField.getSignature())
    }

    override fun getStaticLongField(vm: BaseVM, dvmClass: DvmClass, signature: String): Long {
        throw UnsupportedOperationException(signature)
    }

    override fun toReflectedMethod(vm: BaseVM, dvmClass: DvmClass, dvmMethod: DvmMethod): DvmObject<*> {
        return toReflectedMethod(vm, dvmClass, dvmMethod.getSignature())
    }

    override fun toReflectedMethod(vm: BaseVM, dvmClass: DvmClass, signature: String): DvmObject<*> {
        throw UnsupportedOperationException(signature)
    }

    override fun acceptMethod(dvmClass: DvmClass, signature: String, isStatic: Boolean): Boolean {
        return true
    }

    override fun acceptField(dvmClass: DvmClass, signature: String, isStatic: Boolean): Boolean {
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(AbstractJni::class.java)
    }
}
