package com.vortexdbg.linux.android.dvm

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.linux.android.dvm.apk.AssetResolver
import com.vortexdbg.spi.LibraryFile
import com.sun.jna.Pointer
import net.dongliu.apk.parser.bean.CertificateMeta

import java.io.File

@Suppress("unused")
interface VM {

    fun getJavaVM(): Pointer

    fun getJNIEnv(): Pointer

    /**
     * @param interfaceClasses 如果不为空的话，第一个为superClass，其它的为interfaces
     */
    fun resolveClass(className: String, vararg interfaceClasses: DvmClass): DvmClass

    fun setHashFunction(hashFunction: HashFunction?)

    fun findClass(className: String): DvmClass?

    fun <T : DvmObject<*>> getObject(hash: Int): T

    /**
     * Use vm.setDvmClassFactory(new ProxyClassFactory()) instead
     */
    fun setJni(jni: Jni)

    fun printMemoryInfo()

    fun loadLibrary(libname: String, forceCallInit: Boolean): DalvikModule
    fun loadLibrary(libname: String, raw: ByteArray?, forceCallInit: Boolean): DalvikModule
    fun loadLibrary(elfFile: File, forceCallInit: Boolean): DalvikModule

    fun findLibrary(soName: String): LibraryFile?

    fun addLocalObject(`object`: DvmObject<*>?): Int
    fun addGlobalObject(`object`: DvmObject<*>?): Int

    fun callJNI_OnLoad(emulator: Emulator<*>, module: Module)

    /**
     * 设置apkFile以后，可调用该值获取apk对应的packageName
     */
    fun getPackageName(): String?
    fun getVersionName(): String?
    fun getVersionCode(): Long

    /**
     * 设置apkFile以后，可调用该方法获取资源文件
     * @return 可返回null
     */
    fun openAsset(fileName: String): ByteArray?

    /**
     * 设置apkFile以后，可调用该方法获取压缩包内容
     * @return 可返回null
     */
    fun unzip(path: String): ByteArray?

    fun setAssetResolver(assetResolver: AssetResolver)

    /**
     * 设置apkFile以后，可调用该方法获取AndroidManifest.xml
     * @return 可返回null
     */
    fun getManifestXml(): String?

    /**
     * Add not found class
     * @param className eg: sun/security/pkcs/PKCS7
     */
    fun addNotFoundClass(className: String)

    /**
     * VM throw exception
     */
    fun throwException(throwable: DvmObject<*>?)

    /**
     * Vortex-DBG (A1): propagação de exceção native-&gt;host.
     * Quando habilitado, ao retornar de uma chamada JNI com exceção pendente
     * (ThrowNew/Throw não tratada pelo próprio nativo via ExceptionClear), o Vortex
     * lança a exceção no host como {@link VortexJniException}. Opt-in (default false)
     * para preservar o comportamento do UniDBG upstream.
     */
    fun setExceptionPropagation(enabled: Boolean)
    fun isExceptionPropagation(): Boolean

    /** Exceção JNI pendente (ou null) — espelha ExceptionOccurred/ExceptionCheck. */
    fun getPendingException(): DvmObject<*>?

    /** Limpa a exceção pendente (equivalente a ExceptionClear). */
    fun clearPendingException()

    fun setVerbose(verbose: Boolean)
    fun setVerboseMethodOperation(verboseMethodOperation: Boolean)
    fun setVerboseFieldOperation(verboseFieldOperation: Boolean)

    fun setDvmClassFactory(factory: DvmClassFactory)

    fun getEmulator(): Emulator<*>

    fun getSignatures(): kotlin.Array<CertificateMeta>?

    companion object {
        const val JNI_FALSE = 0
        const val JNI_TRUE = 1
        const val JNI_OK = 0
        const val JNI_ERR = -1 /* unknown error */
        const val JNI_NULL = 0
        const val JNI_COMMIT = 1
        const val JNI_ABORT = 2

        const val JNI_VERSION_1_1 = 0x00010001
        const val JNI_VERSION_1_2 = 0x00010002
        const val JNI_VERSION_1_4 = 0x00010004
        const val JNI_VERSION_1_6 = 0x00010006
        const val JNI_VERSION_1_8 = 0x00010008

        const val JNIInvalidRefType = 0 // 无效引用
        const val JNILocalRefType = 1 // 本地引用
        const val JNIGlobalRefType = 2 // 全局引用
        const val JNIWeakGlobalRefType = 3
    }
}
