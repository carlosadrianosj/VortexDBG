package com.example.mcpdemo;

/**
 * The Java side reached by native code through the JNI bridge, plus a couple of static config
 * fields. Used by the MCP test suite to exercise native->Java callbacks (trace/mock/break) and
 * the field tools.
 */
public class Device {

    /** Static fields, for dvm_read_field / dvm_get_field / dvm_set_field (static). */
    public static int API_LEVEL = 23;
    public static String BUILD_TAG = "mcpdemo-1.0";

    /** native -> Java: a secret salt provided by the Java layer (called back from Vault.seal). */
    public static byte[] salt() {
        return new byte[]{(byte) 0x5A, (byte) 0xA5, 0x13, 0x37, (byte) 0xC0, (byte) 0xDE, 0x42, 0x69};
    }

    /** native -> Java: final hex encoding done by the Java layer (called back from Vault.seal). */
    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
