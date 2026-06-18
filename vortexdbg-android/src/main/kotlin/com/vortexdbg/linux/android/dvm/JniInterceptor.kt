package com.vortexdbg.linux.android.dvm

/**
 * Wraps a selected [Jni] (the class-specific jni or the global vm.jni) so tools can observe or
 * override native -> Java callbacks regardless of whether a class carries its own ProxyJni.
 *
 * Registered in [BaseVM.jniInterceptors] and applied by `checkJni` for EVERY native -> Java call,
 * which is what makes the MCP trace/mock/spoof hooks work even under a ProxyClassFactory (where the
 * app classes have a class-specific jni that would otherwise bypass `vm.jni`).
 */
fun interface JniInterceptor {
    /** Return a [Jni] that wraps [base]; [base] is the jni `checkJni` selected for this call. */
    fun wrap(base: Jni): Jni
}
