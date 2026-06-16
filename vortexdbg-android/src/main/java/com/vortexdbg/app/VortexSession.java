package com.vortexdbg.app;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.arm.backend.BackendFactory;
import com.vortexdbg.linux.android.AndroidEmulatorBuilder;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.dvm.DalvikModule;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.linux.android.dvm.jni.ProxyClassFactory;
import com.vortexdbg.memory.Memory;

import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
public class VortexSession implements Closeable {

    private final AndroidEmulator emulator;
    private final VM vm;
    private final VortexClassLoader classLoader;
    private final VortexInvoker invoker;
    private final List<DalvikModule> nativeModules;

    private VortexSession(AndroidEmulator emulator, VM vm, VortexClassLoader classLoader,
                          List<DalvikModule> nativeModules) {
        this.emulator = emulator;
        this.vm = vm;
        this.classLoader = classLoader;
        this.invoker = new VortexInvoker(classLoader);
        this.nativeModules = nativeModules;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- API pós-open ----

    public AndroidEmulator emulator() { return emulator; }
    public VM vm() { return vm; }
    public VortexClassLoader classLoader() { return classLoader; }
    public VortexInvoker invoker() { return invoker; }
    public List<DalvikModule> nativeModules() { return nativeModules; }

    /** Carrega uma classe do app na JVM host. */
    public Class<?> loadAppClass(String binaryName) {
        return classLoader.loadApp(binaryName);
    }

    /** Invoca um método estático de uma classe do app (estilo LSPosed, off-device). */
    public Object invokeStatic(String className, String method, Class<?>[] paramTypes, Object... args) throws Exception {
        return invoker.invokeStatic(className, method, paramTypes, args);
    }

    /** Resolve uma DvmClass (nome JNI com '/') para chamadas vindas do lado nativo. */
    public DvmClass resolveNativeClass(String jniName) {
        return vm.resolveClass(jniName);
    }

    @Override
    public void close() {
        try {
            emulator.close();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    // ---- Builder ----

    public static class Builder {
        private final List<File> classes = new ArrayList<>();
        private final List<File> nativeLibs = new ArrayList<>();
        private File androidAll;
        private int sdk = 23;
        private boolean is64 = true;
        private boolean verbose = false;
        private boolean exceptionPropagation = true;
        private boolean callJniOnLoad = false;
        private BackendFactory backendFactory;
        private String processName = "vortex";
        private File rootDir = new File("target/rootfs");

        public Builder classes(File... jarsOrDirs) {
            if (jarsOrDirs != null) {
                for (File f : jarsOrDirs) if (f != null) classes.add(f);
            }
            return this;
        }

        public Builder androidAll(File jar) { this.androidAll = jar; return this; }
        public Builder nativeLib(File... so) {
            if (so != null) for (File f : so) if (f != null) nativeLibs.add(f);
            return this;
        }
        public Builder sdk(int sdk) { this.sdk = sdk; return this; }
        public Builder arch64(boolean is64) { this.is64 = is64; return this; }
        public Builder verbose(boolean verbose) { this.verbose = verbose; return this; }
        public Builder exceptionPropagation(boolean enabled) { this.exceptionPropagation = enabled; return this; }
        public Builder callJniOnLoad(boolean enabled) { this.callJniOnLoad = enabled; return this; }
        public Builder backend(BackendFactory factory) { this.backendFactory = factory; return this; }
        public Builder processName(String name) { this.processName = name; return this; }
        public Builder rootDir(File dir) { this.rootDir = dir; return this; }

        public VortexSession open() {
            AndroidEmulatorBuilder eb = is64 ? AndroidEmulatorBuilder.for64Bit() : AndroidEmulatorBuilder.for32Bit();
            eb.setProcessName(processName);
            eb.setRootDir(rootDir);
            eb.addBackendFactory(backendFactory != null ? backendFactory : defaultBackend());
            AndroidEmulator emulator = eb.build();

            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(sdk));

            VM vm = emulator.createDalvikVM();
            vm.setVerbose(verbose);
            vm.setExceptionPropagation(exceptionPropagation);

            VortexClassLoader cl;
            if (androidAll != null) {
                cl = VortexFramework.fromAndroidAll(androidAll).newAppClassLoader(classes.toArray(new File[0]));
            } else {
                cl = new VortexClassLoader(classes.toArray(new File[0]));
            }
            vm.setDvmClassFactory(new ProxyClassFactory(cl));

            List<DalvikModule> modules = new ArrayList<>();
            for (File so : nativeLibs) {
                DalvikModule dm = vm.loadLibrary(so, callJniOnLoad);
                if (callJniOnLoad) {
                    dm.callJNI_OnLoad(emulator);
                }
                modules.add(dm);
            }

            return new VortexSession(emulator, vm, cl, modules);
        }

        private static BackendFactory defaultBackend() {
            try {
                Class<?> c = Class.forName("com.vortexdbg.arm.backend.Unicorn2Factory");
                return (BackendFactory) c.getConstructor(boolean.class).newInstance(true);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "backend padrão (Unicorn2Factory) indisponível no classpath; use .backend(...)", e);
            }
        }
    }
}
