package com.vortexdbg

/**
 * Target platform family. Kotlin's `val` generates the Java getters
 * (`getLibraryExtension()` / `getLibraryPath()`) used by the Java callers.
 */
enum class Family(
    val libraryExtension: String,
    val libraryPath: String,
) {
    Android32(".so", "/android/lib/armeabi-v7a/"),
    Android64(".so", "/android/lib/arm64-v8a/"),
}
