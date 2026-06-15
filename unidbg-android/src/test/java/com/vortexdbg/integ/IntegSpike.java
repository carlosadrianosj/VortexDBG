package com.vortexdbg.integ;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.app.VortexClassLoader;
import com.vortexdbg.app.VortexInvoker;
import com.vortexdbg.arm.backend.BackendFactory;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.linux.android.AndroidARM64Emulator;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.DvmObject;
import com.vortexdbg.linux.android.dvm.StringObject;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.linux.android.dvm.jni.ProxyClassFactory;
import com.vortexdbg.memory.Memory;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

/**
 * CAPSTONE (Vortex-DBG / A1) — stack completo native ↔ app-Java ↔ framework.
 *
 * Junta tudo: o UniDBG emula libinteg.so (ARM64); o .so chama, via JNI,
 * CLASSES REAIS do app (org.cf.*) carregadas na JVM host pelo VortexClassLoader,
 * com a camada de framework (android-all) no classpath — resolvidas pelo
 * ProxyClassFactory apontado ao VortexClassLoader.
 *
 *   T1) native -> org.cf.crypto.XORCrypt.encode(String,String)       [native -> app]
 *   T2) native -> org.cf.obfuscated.StringHolder.get(int)            [native -> app -> framework]
 *
 * Requer JDK 21 no runtime (android-all é Java 11).
 */
public class IntegSpike {

    public static void main(String[] args) throws Exception {
        int pass = 0, fail = 0;

        File appJar = new File("wf2-spike/obfuscated-app.jar");
        File androidAll = new File(new String(Files.readAllBytes(new File("/tmp/android_all_jar.txt").toPath())).trim());
        File soFile = new File("integ-spike/libinteg.so");

        AndroidEmulator emulator = new AndroidARM64Emulator("integ",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));

            VM vm = emulator.createDalvikVM();
            vm.setVerbose(false);

            // app (.class) + framework (android-all) na JVM host; o ProxyClassFactory
            // resolve FindClass/CallStaticMethod do nativo contra ESSAS classes reais.
            VortexClassLoader cl = new VortexClassLoader(appJar, androidAll);
            vm.setDvmClassFactory(new ProxyClassFactory(cl));

            vm.loadLibrary(soFile, false); // funções name-exported
            DvmClass cls = vm.resolveClass("com/vortexdbg/integ/IntegSpike");

            VortexInvoker hostInv = new VortexInvoker(cl); // oráculo: mesma lógica direto no host

            System.out.println("============ CAPSTONE — native -> app-Java -> framework ============");
            System.out.println("backend=" + emulator.getBackend());

            // ---- T1: native -> XORCrypt.encode ----
            try {
                String msg = "hello vortex", key = "k3y";
                DvmObject<?> ret = cls.callStaticJniMethodObject(emulator,
                        "nativeXorEncode(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                        new StringObject(vm, msg), new StringObject(vm, key));
                String fromNative = String.valueOf(ret.getValue());
                String fromHost = (String) hostInv.invokeStatic("org.cf.crypto.XORCrypt", "encode",
                        new Class[]{String.class, String.class}, msg, key);
                boolean ok = fromNative.equals(fromHost);
                System.out.println("[T1 native->XORCrypt.encode] native=\"" + fromNative + "\" host=\"" + fromHost + "\"  " + (ok ? "OK" : "FALHOU"));
                if (ok) pass++; else fail++;
            } catch (Throwable t) {
                System.out.println("[T1] EXCEÇÃO: " + t);
                fail++;
            }

            // ---- T2: native -> StringHolder.get (toca framework android.util.Base64) ----
            try {
                DvmObject<?> ret = cls.callStaticJniMethodObject(emulator,
                        "nativeStringGet(I)Ljava/lang/String;", 0);
                String fromNative = String.valueOf(ret.getValue());
                boolean ok = fromNative != null && !fromNative.isEmpty() && !"null".equals(fromNative);
                System.out.println("[T2 native->StringHolder.get(0)->framework] = \"" + fromNative + "\"  " + (ok ? "OK" : "FALHOU"));
                if (ok) pass++; else fail++;
            } catch (Throwable t) {
                System.out.println("[T2] EXCEÇÃO: " + t);
                fail++;
            }

            System.out.println("===================================================================");
            System.out.println("RESULTADO CAPSTONE: pass=" + pass + " fail=" + fail);
        } finally {
            try { emulator.close(); } catch (Exception ignored) {}
        }
    }
}
