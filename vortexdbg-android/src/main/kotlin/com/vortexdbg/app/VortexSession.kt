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
 * Fachada de orquestração do Vortex-DBG (A1) — consolida os spikes WF1..Capstone numa
 * API única e usável.
 *
 * Um {@code VortexSession} monta, num único processo hermético e off-device:
 *   - o emulador nativo (UniDBG, backend Unicorn2 por padrão);
 *   - a Dalvik VM com a ponte JNI (propagação de exceção ligada);
 *   - o {@link VortexClassLoader} com as classes REAIS do app (extraídas via JEB/dex2jar)
 *     + a camada de framework ({@link VortexFramework} / android-all);
 *   - o {@link ProxyClassFactory} apontado a esse classloader, para que o FindClass/
 *     CallMethod do código nativo resolva as classes reais do app;
 *   - as bibliotecas nativas (.so) do app.
 *
 * Uso típico:
 * <pre>
 *   try (VortexSession s = VortexSession.builder()
 *           .classes(new File("app-classes.jar"))
 *           .androidAll(new File("android-all.jar"))
 *           .nativeLib(new File("libfoo.so"))
 *           .open()) {
 *       Object r = s.invokeStatic("com.app.Crypto", "decrypt",
 *               new Class[]{String.class}, "deadbeef");
 *   }
 * </pre>
 */
open class VortexSession private constructor(
    private val emulator: AndroidEmulator,
    private val vm: VM,
    private val classLoader: VortexClassLoader,
    private val nativeModules: List<DalvikModule>
) : Closeable {

    private val invoker: VortexInvoker = VortexInvoker(classLoader)

    // ---- API pós-open ----

    open fun emulator(): AndroidEmulator { return emulator }
    open fun vm(): VM { return vm }
    open fun classLoader(): VortexClassLoader { return classLoader }
    open fun invoker(): VortexInvoker { return invoker }
    open fun nativeModules(): List<DalvikModule> { return nativeModules }

    /** Carrega uma classe do app na JVM host. */
    open fun loadAppClass(binaryName: String): Class<*> {
        return classLoader.loadApp(binaryName)
    }

    /** Invoca um método estático de uma classe do app (estilo LSPosed, off-device). */
    @Throws(Exception::class)
    open fun invokeStatic(className: String, method: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? {
        return invoker.invokeStatic(className, method, paramTypes, *args)
    }

    /** Resolve uma DvmClass (nome JNI com '/') para chamadas vindas do lado nativo. */
    open fun resolveNativeClass(jniName: String): DvmClass {
        return vm.resolveClass(jniName)
    }

    override fun close() {
        try {
            emulator.close()
        } catch (ignored: Exception) {
            // best-effort
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
