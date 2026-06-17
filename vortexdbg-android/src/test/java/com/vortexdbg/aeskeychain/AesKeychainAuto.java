package com.vortexdbg.aeskeychain;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.arm.backend.Unicorn2Factory;
import com.vortexdbg.linux.android.AndroidEmulatorBuilder;
import com.vortexdbg.linux.android.AndroidResolver;
import com.vortexdbg.linux.android.dvm.DalvikModule;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.VM;
import com.vortexdbg.linux.android.dvm.array.ByteArray;
import com.vortexdbg.memory.Memory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Vortex-DBG end-to-end "SecureVault" demo: a MIXED Java + native keychain.
 *   - block()/combine()  : JAVA, run here on the host JVM (mirrors the app's SecureVault).
 *   - ttEncrypt()         : NATIVE AES in libttEncrypt.so, run on the ARM emulator via the
 *                           DVM/JNI bridge (callStaticJniMethodObject).
 * The native .so is pulled straight out of the signed keychain-aes.apk. We seal a few
 * accounts off-device and verify the output is well-formed and deterministic.
 */
public class AesKeychainAuto {

    // JAVA framing (mirror of com.example.aeskeychain.SecureVault, see tests/keychain-aes-test).
    static byte[] block(String account, String secret) {
        byte[] out = new byte[16];
        byte[] a = account.getBytes();
        byte[] s = secret.getBytes();
        out[0] = (byte) 'V';
        out[1] = (byte) (account.length() & 0xff);
        for (int i = 0; i < 14; i++) {
            int av = i < a.length ? (a[i] & 0xff) : 0x20;
            int sv = i < s.length ? (s[i] & 0xff) : 0x00;
            out[2 + i] = (byte) (av ^ sv);
        }
        return out;
    }

    static String combine(byte[] cipher) {
        int sum = 0;
        for (byte b : cipher) sum = (sum + (b & 0xff)) & 0xff;
        StringBuilder sb = new StringBuilder();
        for (byte x : cipher) sb.append(String.format("%02x", x & 0xff));
        return String.format("%02x", sum) + ":" + sb;
    }

    public static void main(String[] args) throws Exception {
        File apk = new File("tests/keychain-aes-test/out/keychain-aes.apk");
        File soFile = File.createTempFile("libttEncrypt", ".so");
        soFile.deleteOnExit();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("lib/armeabi-v7a/libttEncrypt.so");
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, soFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.out.println("(native lib extracted from " + apk.getName() + ": lib/armeabi-v7a/libttEncrypt.so)");

        AndroidEmulator emulator = AndroidEmulatorBuilder.for32Bit()
                .setProcessName("com.example.aeskeychain")
                .addBackendFactory(new Unicorn2Factory(true))
                .build();
        int pass = 0, fail = 0;
        try {
            Memory memory = emulator.getMemory();
            memory.setLibraryResolver(new AndroidResolver(23));

            VM vm = emulator.createDalvikVM();
            vm.setVerbose(false);
            DalvikModule dm = vm.loadLibrary(soFile, false);
            dm.callJNI_OnLoad(emulator);
            DvmClass tt = vm.resolveClass("com/bytedance/frameworks/core/encrypt/TTEncryptUtils");

            System.out.println("===== Vortex-DBG SecureVault automation (backend=" + emulator.getBackend() + ") =====");
            String[][] creds = {
                    {"alice", "hunter2"},
                    {"bob", "s3cr3t"},
                    {"carol@corp", "p@ssw0rd"},
                    {"user-12345", "letmein"},
            };
            for (String[] c : creds) {
                String account = c[0], secret = c[1];
                byte[] pt = block(account, secret);                         // JAVA (host)
                byte[] ct1 = aes(tt, emulator, vm, pt);                     // NATIVE (emulated)
                byte[] ct2 = aes(tt, emulator, vm, pt);                     // NATIVE again -> determinism
                String token = combine(ct1);                                // JAVA (host)
                boolean ok = ct1.length > 0 && java.util.Arrays.equals(ct1, ct2) && token.matches("[0-9a-f]{2}:[0-9a-f]+");
                System.out.printf("  %-12s / %-9s -> %s   %s%n", account, secret, token, ok ? "OK" : "FAIL");
                if (ok) pass++; else fail++;
            }
            System.out.println("=========================================================================");
            System.out.println("RESULT: pass=" + pass + " fail=" + fail + "  (Java framing + native AES, emulated off-device)");
        } finally {
            try { emulator.close(); } catch (Exception ignored) {}
        }
    }

    static byte[] aes(DvmClass tt, AndroidEmulator emulator, VM vm, byte[] pt) {
        ByteArray out = tt.callStaticJniMethodObject(emulator, "ttEncrypt([BI)[B", new ByteArray(vm, pt), pt.length);
        return out.getValue();
    }
}
