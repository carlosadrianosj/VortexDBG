package com.vortexdbg.linux.android.dvm

import com.sun.jna.Pointer

/**
 * Vortex-DBG (A1 / WF3) — exceção do host que carrega uma exceção JNI pendente
 * lançada pelo código nativo (ThrowNew/Throw) e não tratada via ExceptionClear.
 *
 * É lançada por {@code DvmObject.callJniMethod} quando a propagação de exceção está
 * habilitada ({@link VM#setExceptionPropagation(boolean)}), traduzindo a semântica
 * JNI "exceção pendente" para a semântica de exceção da JVM host.
 */
open class VortexJniException(@Transient private val pendingException: DvmObject<*>?) :
    RuntimeException(describe(pendingException)) {

    /** O DvmObject da exceção nativa pendente. */
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
                // valor não-legível; fica só o tipo
            }
            return if (msg == null) type else "$type: $msg"
        }
    }
}
