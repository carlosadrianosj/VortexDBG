package com.vortexdbg.linux.android.dvm

import com.vortexdbg.AndroidEmulator
import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.linux.android.ElfLibraryFile
import com.vortexdbg.linux.android.ElfLibraryRawFile
import com.vortexdbg.linux.android.dvm.apk.Apk
import com.vortexdbg.linux.android.dvm.apk.ApkFactory
import com.vortexdbg.linux.android.dvm.apk.AssetResolver
import com.vortexdbg.spi.LibraryFile
import net.dongliu.apk.parser.bean.CertificateMeta
import org.slf4j.LoggerFactory

import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage
import java.util.Arrays
import java.util.HashMap
import java.util.HashSet

abstract class BaseVM protected constructor(private val emulator: AndroidEmulator, apkFile: File?) : VM, DvmClassFactory {

    @JvmField
    val classMap: MutableMap<Int, DvmClass> = HashMap()

    @JvmField
    var jni: Jni? = null

    /** Optional wrappers applied to whatever jni checkJni selects (class-specific or global). */
    @JvmField
    val jniInterceptors: MutableList<JniInterceptor> = ArrayList()

    /** Apply registered [jniInterceptors] (innermost first) around [base]. */
    fun interceptJni(base: Jni): Jni {
        if (jniInterceptors.isEmpty()) return base
        var j = base
        for (f in jniInterceptors) j = f.wrap(j)
        return j
    }

    @JvmField
    var throwable: DvmObject<*>? = null

    @JvmField
    var exceptionPropagation: Boolean = false

    @JvmField
    var verbose: Boolean = false

    @JvmField
    var verboseMethodOperation: Boolean = false

    @JvmField
    var verboseFieldOperation: Boolean = false

    override fun setVerbose(verbose: Boolean) {
        this.verbose = verbose
    }

    override fun setVerboseMethodOperation(verboseMethodOperation: Boolean) {
        this.verboseMethodOperation = verboseMethodOperation
    }

    override fun setVerboseFieldOperation(verboseFieldOperation: Boolean) {
        this.verboseFieldOperation = verboseFieldOperation
    }

    override fun throwException(throwable: DvmObject<*>?) {
        this.throwable = throwable
    }

    override fun setExceptionPropagation(enabled: Boolean) {
        this.exceptionPropagation = enabled
    }

    override fun isExceptionPropagation(): Boolean {
        return exceptionPropagation
    }

    override fun getPendingException(): DvmObject<*>? {
        return throwable
    }

    override fun clearPendingException() {
        this.throwable = null
    }

    override fun setJni(jni: Jni) {
        this.jni = jni
    }

    private val apk: Apk? = if (apkFile == null) null else ApkFactory.createApk(apkFile)

    @JvmField
    val notFoundClassSet: MutableSet<String> = HashSet()

    override fun addNotFoundClass(className: String) {
        notFoundClassSet.add(className)
    }

    class ObjRef(@JvmField val obj: DvmObject<*>, @JvmField val weak: Boolean) {
        @JvmField
        var refCount: Int = 1

        override fun toString(): String {
            return obj.toString()
        }
    }

    @JvmField
    val globalObjectMap: MutableMap<Int, ObjRef> = HashMap()

    @JvmField
    val weakGlobalObjectMap: MutableMap<Int, ObjRef> = HashMap()

    @JvmField
    val localObjectMap: MutableMap<Int, ObjRef> = HashMap()

    private var dvmClassFactory: DvmClassFactory? = null

    override fun setDvmClassFactory(factory: DvmClassFactory) {
        this.dvmClassFactory = factory
    }

    private var hashFunction: HashFunction = Hasher.Default

    override fun setHashFunction(hashFunction: HashFunction?) {
        if (hashFunction == null) {
            throw NullPointerException("hashFunction == null")
        }
        if (!classMap.isEmpty()) {
            throw IllegalStateException("Must set hash function before resolving any class")
        }
        this.hashFunction = hashFunction
    }

    fun hash(className: String): Int {
        return hashFunction.hash(className)
    }

    override fun resolveClass(className: String, vararg interfaceClasses: DvmClass): DvmClass {
        val name = className.replace('.', '/')
        val hash = this.hash(name)
        var dvmClass = classMap[hash]
        var superClass: DvmClass? = null
        var interfaces: kotlin.Array<DvmClass> = arrayOf(*interfaceClasses)
        if (interfaces.isNotEmpty()) {
            superClass = interfaces[0]
            interfaces = Arrays.copyOfRange(interfaces, 1, interfaces.size)
        }
        if (dvmClass == null) {
            if (dvmClassFactory != null) {
                dvmClass = dvmClassFactory!!.createClass(this, name, superClass, interfaces)
            }
            if (dvmClass == null) {
                dvmClass = this.createClass(this, name, superClass, interfaces)
            }
            val oldClass = classMap.put(hash, dvmClass)
            if (oldClass != null && oldClass.getClassName() != name) {
                throw IllegalStateException("Hash collision: " + oldClass.getClassName() + " and " + name + " have the same hash=0x" + Integer.toHexString(hash))
            }
        }
        addGlobalObject(dvmClass)
        return dvmClass
    }

    override fun createClass(vm: BaseVM, className: String, superClass: DvmClass?, interfaceClasses: kotlin.Array<DvmClass>?): DvmClass {
        return DvmClass(vm, className, superClass, interfaceClasses)
    }

    fun addObject(`object`: DvmObject<*>, global: Boolean, weak: Boolean): Int {
        val hash = `object`.hashCode()
        if (log.isDebugEnabled) {
            log.debug("addObject hash=0x{}, global={}", java.lang.Long.toHexString(hash.toLong()), global)
        }
        val value = `object`.getValue()
        if (value is DvmAwareObject) {
            value.initializeDvm(emulator, this, `object`)
        }
        if (global) {
            var old = if (weak) weakGlobalObjectMap[hash] else globalObjectMap[hash]
            if (old == null) {
                old = ObjRef(`object`, weak)
            } else {
                old.refCount++
            }
            if (weak) {
                weakGlobalObjectMap[hash] = old
            } else {
                globalObjectMap[hash] = old
            }
        } else {
            localObjectMap[hash] = ObjRef(`object`, weak)
        }
        return hash
    }

    override fun addLocalObject(`object`: DvmObject<*>?): Int {
        if (`object` == null) {
            return VM.JNI_NULL
        }

        return addObject(`object`, false, false)
    }

    override fun addGlobalObject(`object`: DvmObject<*>?): Int {
        if (`object` == null) {
            return VM.JNI_NULL
        }

        return addObject(`object`, true, false)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : DvmObject<*>> getObject(hash: Int): T {
        val ref: ObjRef?
        if (localObjectMap.containsKey(hash)) {
            ref = localObjectMap[hash]
        } else if (globalObjectMap.containsKey(hash)) {
            ref = globalObjectMap[hash]
        } else {
            ref = weakGlobalObjectMap[hash]
        }
        return (if (ref == null) null else ref.obj) as T
    }

    override fun findClass(className: String): DvmClass? {
        return classMap[this.hash(className)]
    }

    fun deleteLocalRefs() {
        for (ref in localObjectMap.values) {
            ref.obj.onDeleteRef()
        }
        localObjectMap.clear()

        if (throwable != null) {
            throwable!!.onDeleteRef()
            throwable = null
        }
    }

    fun checkVersion(version: Int) {
        if (version != VM.JNI_VERSION_1_1 &&
            version != VM.JNI_VERSION_1_2 &&
            version != VM.JNI_VERSION_1_4 &&
            version != VM.JNI_VERSION_1_6 &&
            version != VM.JNI_VERSION_1_8
        ) {
            if (log.isTraceEnabled) {
                emulator.attach().debug("Illegal JNI version: 0x" + Integer.toHexString(version))
            }
            throw IllegalStateException("Illegal JNI version: 0x" + Integer.toHexString(version))
        }
    }

    internal abstract fun loadLibraryData(apk: Apk, soName: String): ByteArray?

    override fun findLibrary(soName: String): LibraryFile? {
        if (apk == null) {
            throw UnsupportedOperationException()
        }

        var libraryFile = findLibrary(apk, soName)
        if (libraryFile == null) {
            val split = File(apk.getParentFile(), if (emulator.is64Bit()) "config.arm64_v8a.apk" else "config.armeabi_v7a.apk")
            if (split.canRead()) {
                libraryFile = findLibrary(ApkFactory.createApk(split), soName)
            }
        }
        return libraryFile
    }

    override fun loadLibrary(libname: String, forceCallInit: Boolean): DalvikModule {
        val soName = "lib" + libname + ".so"
        val libraryFile = findLibrary(soName)
            ?: throw IllegalStateException("load library failed: " + libname)
        val module = emulator.getMemory().load(libraryFile, forceCallInit)
        return DalvikModule(this, module)
    }

    override fun loadLibrary(libname: String, raw: ByteArray?, forceCallInit: Boolean): DalvikModule {
        if (raw == null || raw.size == 0) {
            throw IllegalArgumentException()
        }
        val module = emulator.getMemory().load(ElfLibraryRawFile(libname, raw, emulator.is64Bit()), forceCallInit)
        return DalvikModule(this, module)
    }

    private fun findLibrary(apk: Apk, soName: String): ApkLibraryFile? {
        val libData = loadLibraryData(apk, soName) ?: return null

        return ApkLibraryFile(this, apk, soName, libData, apk.getPackageName(), emulator.is64Bit())
    }

    override fun getSignatures(): kotlin.Array<CertificateMeta>? {
        return if (apk == null) null else apk.getSignatures()
    }

    override fun getPackageName(): String? {
        return if (apk == null) null else apk.getPackageName()
    }

    override fun getManifestXml(): String? {
        return if (apk == null) null else apk.getManifestXml()
    }

    override fun openAsset(fileName: String): ByteArray? {
        if (assetResolver != null) {
            val bytes = assetResolver!!.resolveAsset(fileName)
            if (bytes != null) {
                return bytes
            }
        }

        return if (apk == null) null else apk.openAsset(fileName)
    }

    override fun unzip(path: String): ByteArray? {
        var p = path
        if (p.length > 1 && p[0] == '/') {
            p = p.substring(1)
        }
        return if (apk == null) null else apk.getFileData(p)
    }

    private var assetResolver: AssetResolver? = null

    override fun setAssetResolver(assetResolver: AssetResolver) {
        this.assetResolver = assetResolver
    }

    override fun getVersionName(): String? {
        return if (apk == null) null else apk.getVersionName()
    }

    override fun getVersionCode(): Long {
        return if (apk == null) 0 else apk.getVersionCode()
    }

    override fun loadLibrary(elfFile: File, forceCallInit: Boolean): DalvikModule {
        val module = emulator.getMemory().load(ElfLibraryFile(elfFile, emulator.is64Bit()), forceCallInit)
        return DalvikModule(this, module)
    }

    override fun printMemoryInfo() {
        System.gc()
        val memoryMXBean = ManagementFactory.getMemoryMXBean()
        val heap = memoryMXBean.heapMemoryUsage
        val nonHeap = memoryMXBean.nonHeapMemoryUsage
        val map: MutableMap<Int, ObjRef> = HashMap(globalObjectMap)
        for (key in classMap.keys) {
            map.remove(key)
        }
        System.err.println("globalObjectSize=" + globalObjectMap.size + ", localObjectSize=" + localObjectMap.size + ", weakGlobalObjectSize=" + weakGlobalObjectMap.size + ", classSize=" + classMap.size + ", globalObjectSize=" + map.size)
        System.err.println("heap: " + memoryUsage(heap) + ", nonHeap: " + memoryUsage(nonHeap))
    }

    private fun toMB(memory: Long): String {
        return ((memory * 100 / (1024 * 1024)) / 100f).toString() + "MB"
    }

    private fun memoryUsage(usage: MemoryUsage): String {
        return ("init=" + toMB(usage.init) + ", used="
                + toMB(usage.used) + ", committed="
                + toMB(usage.committed) + ", max="
                + toMB(usage.max))
    }

    override fun callJNI_OnLoad(emulator: Emulator<*>, module: Module) {
        DalvikModule(this, module).callJNI_OnLoad(emulator)
    }

    override fun getEmulator(): Emulator<*> {
        return emulator
    }

    companion object {
        private val log = LoggerFactory.getLogger(BaseVM::class.java)

        @JvmStatic
        fun valueOf(value: Int): Boolean {
            return when (value) {
                VM.JNI_TRUE -> true
                VM.JNI_FALSE -> false
                else -> throw IllegalStateException("Invalid boolean value=" + value)
            }
        }
    }
}
