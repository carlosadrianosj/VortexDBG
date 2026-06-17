package com.vortexdbg.keychain;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.app.VortexClassLoader;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Vortex-DBG end-to-end: a "real app" (KeyChain) whose key-derivation is MIXED
 * Java + native and BIDIRECTIONAL:
 *   host -> KeyChain.generate(account)  [Java -> native, emulated .so]
 *          -> KeyChain.salt() / KeyChain.hex()  [native -> Java, on host JVM]
 * Vortex-DBG emulates the native .so and runs the app's Java on the host, so we
 * can AUTOMATE keychain generation for any account, fully off-device.
 */
public class KeyChainAuto {

    // Independent Java oracle replicating the C derivation (to verify the emulation).
    static String oracle(String account) {
        byte[] salt = {(byte) 0x5A, (byte) 0xA5, 0x13, 0x37, (byte) 0xC0, (byte) 0xDE, 0x42, 0x69};
        int n = salt.length;
        byte[] acc = account.getBytes();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int a = i < acc.length ? (acc[i] & 0xff) : 0xA7;
            out[i] = (a ^ (salt[i] & 0xff)) & 0xff;
        }
        for (int r = 0; r < 3; r++) {
            for (int i = 0; i < n; i++) {
                int v = out[i] & 0xff;
                v = ((v << 3) | (v >> 5)) & 0xff;
                v = (v + (salt[(i + r) % n] & 0xff) + (r * 7 + 1)) & 0xff;
                out[i] = v;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int x : out) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        File appJar = new File("tests/keychain-app/out/keychain.jar");
        // Pull the native lib straight out of the real signed APK:
        File apk = new File("tests/keychain-app/out/keychain.apk");
        File soFile = File.createTempFile("libkeychain", ".so");
        soFile.deleteOnExit();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libkeychain.so");
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, soFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.out.println("(native lib extracted from " + apk.getName() + ": lib/arm64-v8a/libkeychain.so)");

        AndroidEmulator emulator = new AndroidARM64Emulator("keychain",
                new File("target/rootfs"),
                Collections.<BackendFactory>singletonList(new Unicorn2Factory(true))) {};
        int pass = 0, fail = 0;
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));

            VM vm = emulator.createDalvikVM();
            vm.setVerbose(false);
            vm.setDvmClassFactory(new ProxyClassFactory(new VortexClassLoader(appJar)));
            vm.loadLibrary(soFile, false);
            DvmClass cls = vm.resolveClass("com/example/keychain/KeyChain");

            System.out.println("===== Vortex-DBG keychain automation (backend=" + emulator.getBackend() + ") =====");
            String[] accounts = {"alice", "bob", "carol@corp", "user-12345", "root"};
            for (String acc : accounts) {
                DvmObject<?> ret = cls.callStaticJniMethodObject(emulator,
                        "generate(Ljava/lang/String;)Ljava/lang/String;", new StringObject(vm, acc));
                String kc = String.valueOf(ret.getValue());
                String exp = oracle(acc);
                boolean ok = exp.equals(kc);
                System.out.printf("  %-12s -> %s   %s%n", acc, kc, ok ? "OK" : "MISMATCH(exp " + exp + ")");
                if (ok) pass++; else fail++;
            }
            System.out.println("=========================================================================");
            System.out.println("RESULT: pass=" + pass + " fail=" + fail + "  (Java<->native keychain emulated off-device)");
        } finally {
            try { emulator.close(); } catch (Exception ignored) {}
        }
    }
}
