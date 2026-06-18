package com.example.secure;

/**
 * A realistic C++ native target for the MCP suite: libsecure.so is written in C++ and uses
 * std::string / std::vector internally. process() runs a rolling-key stream cipher and stashes the
 * last plaintext in a real libc++ std::string global, so read_std_string can be exercised against an
 * ACTUAL std::string in emulated memory (short = SSO, long = heap), not a hand-crafted blob.
 */
public class Secure {

    /** Encrypts input with a rolling-key XOR and returns the hex ciphertext (runs in libsecure.so). */
    public static native String process(String input);
}
