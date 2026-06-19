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
     * @param interfaceClasses when non-empty, the first entry is the superclass and the rest are interfaces
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
     * Returns the APK package name; available only after an apkFile has been set.
     */
    fun getPackageName(): String?
    fun getVersionName(): String?
    fun getVersionCode(): Long

    /**
     * Reads an asset file from the configured apkFile.
     * @return null when unavailable
     */
    fun openAsset(fileName: String): ByteArray?

    /**
     * Reads an entry from the configured apkFile archive.
     * @return null when unavailable
     */
    fun unzip(path: String): ByteArray?

    fun setAssetResolver(assetResolver: AssetResolver)

    /**
     * Reads AndroidManifest.xml from the configured apkFile.
     * @return null when unavailable
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
     * Vortex-DBG (A1): native-&gt;host exception propagation.
     * When enabled, returning from a JNI call that left a pending exception
     * (a ThrowNew/Throw the native code did not handle via ExceptionClear) makes
     * Vortex rethrow it on the host as a {@link VortexJniException}. Opt-in
     * (default false) to preserve upstream UniDBG behaviour.
     */
    fun setExceptionPropagation(enabled: Boolean)
    fun isExceptionPropagation(): Boolean

    /** The pending JNI exception, or null; mirrors ExceptionOccurred/ExceptionCheck. */
    fun getPendingException(): DvmObject<*>?

    /** Clears the pending exception (equivalent to ExceptionClear). */
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

        const val JNIInvalidRefType = 0 // invalid reference
        const val JNILocalRefType = 1 // local reference
        const val JNIGlobalRefType = 2 // global reference
        const val JNIWeakGlobalRefType = 3
    }
}
