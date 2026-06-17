package com.example.keychain;

/**
 * Mixed Java/native keychain derivation.
 *  - generate(account): NATIVE entry (emulated by Vortex-DBG).
 *  - the native code calls BACK into Java: salt() and hex() (native -> Java).
 * So a keychain depends on BOTH the native mixing AND the Java salt/hex,
 * which is exactly what Vortex-DBG reproduces off-device.
 */
public class KeyChain {

    /** Java -> native: the derivation core runs in the .so. */
    public static native String generate(String account);

    /** native -> Java: secret salt provided by the Java layer. */
    public static byte[] salt() {
        return new byte[]{(byte) 0x5A, (byte) 0xA5, 0x13, 0x37, (byte) 0xC0, (byte) 0xDE, 0x42, 0x69};
    }

    /** native -> Java: final hex encoding done by the Java layer. */
    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
