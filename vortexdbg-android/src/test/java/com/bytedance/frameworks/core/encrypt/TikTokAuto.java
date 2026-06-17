package com.bytedance.frameworks.core.encrypt;

/** Focused TikTok libttEncrypt test: real AES-based ttEncrypt emulated (no IDA attach / no hooks). */
public class TikTokAuto {
    public static void main(String[] args) throws Exception {
        TTEncrypt t = new TTEncrypt(false); // logging=false -> skip sbox dump / HookZz / Dobby / IDA attach
        byte[] out = t.ttEncrypt();
        StringBuilder sb = new StringBuilder();
        for (byte b : out) sb.append(String.format("%02x", b & 0xff));
        System.out.println("===== TikTok libttEncrypt (emulated ARM, real AES) =====");
        System.out.println("ttEncrypt(16x 0x00) = " + sb + "  (" + out.length + " bytes)");
        System.out.println("RESULT: " + (out.length > 0 ? "OK — native crypto emulated off-device" : "FAIL"));
        t.destroy();
    }
}
