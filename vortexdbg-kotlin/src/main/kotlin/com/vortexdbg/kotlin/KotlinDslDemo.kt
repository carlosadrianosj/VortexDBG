package com.vortexdbg.kotlin

import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.array.ByteArray as DvmByteArray

/**
 * Demo de runtime do piloto Kotlin: abre uma VortexSession via DSL e roda o libttEncrypt
 * REAL (TikTok) — provando interop Kotlin -> API Java do Vortex em runtime, não só compilação.
 *
 * Rodar (JDK 8): java ... com.vortexdbg.kotlin.KotlinDslDemo
 */
object KotlinDslDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        val so = "vortexdbg-android/src/test/resources/example_binaries/libttEncrypt.so"

        vortexSession {
            arch64 = false
            processName = "com.qidian.dldl.official"
            sdk = 23
            callJniOnLoad = true
            nativeLib(so)
        }.use { s ->
            println("=== Kotlin DSL -> VortexSession (backend=${s.emulator().getBackend()}) ===")
            val data = ByteArray(16)
            val ret = s.resolveNativeClass("com/bytedance/frameworks/core/encrypt/TTEncryptUtils")
                .callStaticJniMethodObject<DvmObject<*>>(
                    s.emulator(), "ttEncrypt([BI)[B", DvmByteArray(s.vm(), data), data.size,
                )
            val out = ret.getValue() as ByteArray
            val hex = out.joinToString("") { "%02x".format(it) }
            println("ttEncrypt(16x00) = $hex")
            println("RESULTADO Kotlin piloto: ${if (hex.isNotEmpty()) "OK (Kotlin DSL roda o emulador via API Java)" else "FALHOU"}")
        }
    }
}
