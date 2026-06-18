#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <cstddef>

/*
 * libsecure.so - a real C++ native target. Uses std::string / std::vector internally, and keeps the
 * last processed plaintext in a libc++ std::string GLOBAL so read_std_string can read a real
 * std::string (SSO for short inputs, heap for long ones). secure_plaintext_addr() exports its
 * address so an MCP client can locate it with find_symbol/call_symbol then read_std_string.
 */

static std::string g_last_plaintext;

extern "C" const void *secure_plaintext_addr() {
    return reinterpret_cast<const void *>(&g_last_plaintext);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_secure_Secure_process(JNIEnv *env, jclass clazz, jstring input) {
    const char *in = env->GetStringUTFChars(input, nullptr);
    std::string plain(in);
    env->ReleaseStringUTFChars(input, in);

    g_last_plaintext = plain; // stash so read_std_string can read the real std::string

    static const std::vector<uint8_t> key = {0x53, 0x65, 0x63, 0x75, 0x72, 0x65, 0x21}; // "Secure!"
    std::vector<uint8_t> out;
    out.reserve(plain.size());
    uint8_t roll = 0x42;
    for (size_t i = 0; i < plain.size(); i++) {
        uint8_t k = static_cast<uint8_t>(key[i % key.size()] ^ roll);
        uint8_t c = static_cast<uint8_t>(static_cast<uint8_t>(plain[i]) ^ k);
        out.push_back(c);
        roll = static_cast<uint8_t>(roll + c + 7);
    }

    static const char *hex = "0123456789abcdef";
    std::string result;
    result.reserve(out.size() * 2);
    for (size_t i = 0; i < out.size(); i++) {
        result.push_back(hex[out[i] >> 4]);
        result.push_back(hex[out[i] & 0xf]);
    }
    return env->NewStringUTF(result.c_str());
}
