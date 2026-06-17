package com.example.aeskeychain;

import com.bytedance.frameworks.core.encrypt.TTEncryptUtils;

/**
 * SecureVault — a tiny app that "seals" a per-account secret into a keychain token.
 *
 * The logic is intentionally MIXED Java + native, the kind Vortex-DBG reproduces off-device:
 *
 *   1. {@link #block(String, String)}      JAVA   — frame (account, secret) into a 16-byte block.
 *   2. {@link TTEncryptUtils#ttEncrypt}    NATIVE — AES-encrypt the block in libttEncrypt.so
 *                                                   (ByteDance's real crypto, emulated by Vortex).
 *   3. {@link #combine(byte[])}            JAVA   — append a Java checksum tag and hex-encode.
 *
 * So a token depends on BOTH the Java framing/checksum AND the native AES. On a device this is
 * one call to {@link #seal(String, String)}; under Vortex-DBG the native step runs on the ARM
 * emulator while the Java steps run on the host JVM, glued by the JNI bridge (see the harness
 * and the MCP `seal` tool under vortexdbg-android/src/test).
 */
public class SecureVault {

    /** JAVA: frame (account, secret) into a fixed 16-byte plaintext block. */
    public static byte[] block(String account, String secret) {
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

    /** JAVA: additive checksum over the ciphertext, two hex chars. */
    public static String tag(byte[] cipher) {
        int sum = 0;
        for (byte b : cipher) sum = (sum + (b & 0xff)) & 0xff;
        return String.format("%02x", sum);
    }

    /** JAVA: lowercase hex encoding. */
    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /** JAVA: post-process the native ciphertext into the final token "&lt;tag&gt;:&lt;hex&gt;". */
    public static String combine(byte[] cipher) {
        return tag(cipher) + ":" + hex(cipher);
    }

    /**
     * The full on-device pipeline: JAVA framing -&gt; NATIVE AES -&gt; JAVA tag+hex.
     * Vortex-DBG reproduces this by emulating step 2 and running steps 1 and 3 on the host JVM.
     */
    public static String seal(String account, String secret) {
        byte[] pt = block(account, secret);                   // JAVA
        byte[] ct = TTEncryptUtils.ttEncrypt(pt, pt.length);  // NATIVE (emulated .so)
        return combine(ct);                                   // JAVA
    }
}
