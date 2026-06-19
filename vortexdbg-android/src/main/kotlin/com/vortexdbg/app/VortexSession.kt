package com.vortexdbg.app

import com.vortexdbg.AndroidEmulator
import com.vortexdbg.arm.backend.BackendFactory
import com.vortexdbg.linux.android.AndroidEmulatorBuilder
import com.vortexdbg.linux.android.AndroidResolver
import com.vortexdbg.linux.android.dvm.DalvikModule
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.VM
import com.vortexdbg.linux.android.dvm.jni.ProxyClassFactory
import java.io.Closeable
import java.io.File

/**
 * Orchestration facade for Vortex-DBG (A1) — wires the moving parts into a single, usable API.
 *
 * A [VortexSession] assembles, in one hermetic off-device process:
 *   - the native emulator (UniDBG, Unicorn2 backend by default);
 *   - the Dalvik VM with the JNI bridge (exception propagation enabled);
 *   - the [VortexClassLoader] holding the app's REAL classes (extracted via JEB/dex2jar)
 *     plus the framework layer ([VortexFramework] / android-all);
 *   - a [ProxyClassFactory] pointed at that classloader, so FindClass/CallMethod from native
 *     code resolves against the app's real classes;
 *   - the app's native libraries (.so).
 *
 * Typical use:
 * ```
 *   try (VortexSession s = VortexSession.builder()
 *           .classes(new File("app-classes.jar"))
 *           .androidAll(new File("android-all.jar"))
 *           .nativeLib(new File("libfoo.so"))
 *           .open()) {
 *       Object r = s.invokeStatic("com.app.Crypto", "decrypt",
 *               new Class[]{String.class}, "deadbeef");
 *   }
 * ```
 */
open class VortexSession private constructor(
    private val emulator: AndroidEmulator,
    private val vm: VM,
    private val classLoader: VortexClassLoader,
    private val nativeModules: List<DalvikModule>
) : Closeable {

    private val invoker: VortexInvoker = VortexInvoker(classLoader)

    // ---- post-open API ----

    open fun emulator(): AndroidEmulator { return emulator }
    open fun vm(): VM { return vm }
    open fun classLoader(): VortexClassLoader { return classLoader }
    open fun invoker(): VortexInvoker { return invoker }
    open fun nativeModules(): List<DalvikModule> { return nativeModules }

    /** Loads an app class into the host JVM. */
    open fun loadAppClass(binaryName: String): Class<*> {
        return classLoader.loadApp(binaryName)
    }

    /** Invokes a static method on an app class (LSPosed-style, off-device). */
    @Throws(Exception::class)
    open fun invokeStatic(className: String, method: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? {
        return invoker.invokeStatic(className, method, paramTypes, *args)
    }

    /** Resolves a DvmClass (JNI name with '/') for calls coming from the native side. */
    open fun resolveNativeClass(jniName: String): DvmClass {
        return vm.resolveClass(jniName)
    }

    override fun close() {
        try {
            emulator.close()
        } catch (ignored: Exception) {
            // best-effort: closing must not throw
        }
    }

    companion object {
        @JvmStatic
        fun builder(): Builder {
            return Builder()
        }
    }

    // ---- Builder ----

    open class Builder {
        private val classes = ArrayList<File>()
        private val nativeLibs = ArrayList<File>()
        private var androidAll: File? = null
        private var sdk = 23
        private var is64 = true
        private var verbose = false
        private var exceptionPropagation = true
        private var callJniOnLoad = false
        private var backendFactory: BackendFactory? = null
        private var processName = "vortex"
        private var rootDir: File = File("target/rootfs")

        open fun classes(vararg jarsOrDirs: File?): Builder {
            for (f in jarsOrDirs) if (f != null) classes.add(f)
            return this
        }

        open fun androidAll(jar: File?): Builder { this.androidAll = jar; return this }
        open fun nativeLib(vararg so: File?): Builder {
            for (f in so) if (f != null) nativeLibs.add(f)
            return this
        }
        open fun sdk(sdk: Int): Builder { this.sdk = sdk; return this }
        open fun arch64(is64: Boolean): Builder { this.is64 = is64; return this }
        open fun verbose(verbose: Boolean): Builder { this.verbose = verbose; return this }
        open fun exceptionPropagation(enabled: Boolean): Builder { this.exceptionPropagation = enabled; return this }
        open fun callJniOnLoad(enabled: Boolean): Builder { this.callJniOnLoad = enabled; return this }
        open fun backend(factory: BackendFactory?): Builder { this.backendFactory = factory; return this }
        open fun processName(name: String): Builder { this.processName = name; return this }
        open fun rootDir(dir: File): Builder { this.rootDir = dir; return this }

        open fun open(): VortexSession {
            val eb: AndroidEmulatorBuilder = if (is64) AndroidEmulatorBuilder.for64Bit() else AndroidEmulatorBuilder.for32Bit()
            eb.setProcessName(processName)
            eb.setRootDir(rootDir)
            eb.addBackendFactory(backendFactory ?: defaultBackend())
            val emulator = eb.build()

            val memory = emulator.getMemory()
            memory.setLibraryResolver(AndroidResolver(sdk))

            val vm = emulator.createDalvikVM()
            vm.setVerbose(verbose)
            vm.setExceptionPropagation(exceptionPropagation)

            val cl: VortexClassLoader
            if (androidAll != null) {
                cl = VortexFramework.fromAndroidAll(androidAll).newAppClassLoader(*classes.toTypedArray())
            } else {
                cl = VortexClassLoader(*classes.toTypedArray())
            }
            vm.setDvmClassFactory(ProxyClassFactory(cl))

            val modules = ArrayList<DalvikModule>()
            for (so in nativeLibs) {
                val dm = vm.loadLibrary(so, callJniOnLoad)
                if (callJniOnLoad) {
                    dm.callJNI_OnLoad(emulator)
                }
                modules.add(dm)
            }

            return VortexSession(emulator, vm, cl, modules)
        }

        companion object {
            private fun defaultBackend(): BackendFactory {
                try {
                    val c = Class.forName("com.vortexdbg.arm.backend.Unicorn2Factory")
                    return c.getConstructor(Boolean::class.javaPrimitiveType).newInstance(true) as BackendFactory
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "backend padrão (Unicorn2Factory) indisponível no classpath; use .backend(...)", e
                    )
                }
            }
        }
    }
}
