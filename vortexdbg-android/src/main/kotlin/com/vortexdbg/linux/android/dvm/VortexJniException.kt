package com.vortexdbg.linux.android.dvm

import com.sun.jna.Pointer

/**
 * Vortex-DBG (A1 / WF3) — host exception carrying a pending JNI exception that
 * native code raised (ThrowNew/Throw) and never cleared via ExceptionClear.
 *
 * Thrown from {@code DvmObject.callJniMethod} when exception propagation is enabled
 * ({@link VM#setExceptionPropagation(boolean)}), translating the JNI "pending
 * exception" semantics into the host JVM's exception semantics.
 */
open class VortexJniException(@Transient private val pendingException: DvmObject<*>?) :
    RuntimeException(describe(pendingException)) {

    /** The DvmObject of the pending native exception. */
    open fun getPendingException(): DvmObject<*>? {
        return pendingException
    }

    companion object {
        private fun describe(ex: DvmObject<*>?): String {
            if (ex == null) {
                return "native JNI exception"
            }
            val type = if (ex.getObjectType() != null) ex.getObjectType()!!.getName() else ex.javaClass.simpleName
            var msg: String? = null
            try {
                val v = ex.getValue()
                if (v is Pointer) {
                    msg = v.getString(0L)
                } else if (v != null) {
                    msg = v.toString()
                }
            } catch (ignored: Exception) {
                // Value not readable; fall back to the type alone.
            }
            return if (msg == null) type else "$type: $msg"
        }
    }
}
