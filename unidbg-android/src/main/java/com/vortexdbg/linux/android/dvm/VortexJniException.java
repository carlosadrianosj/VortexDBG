package com.vortexdbg.linux.android.dvm;

import com.sun.jna.Pointer;

/**
 * Vortex-DBG (A1 / WF3) — exceção do host que carrega uma exceção JNI pendente
 * lançada pelo código nativo (ThrowNew/Throw) e não tratada via ExceptionClear.
 *
 * É lançada por {@code DvmObject.callJniMethod} quando a propagação de exceção está
 * habilitada ({@link VM#setExceptionPropagation(boolean)}), traduzindo a semântica
 * JNI "exceção pendente" para a semântica de exceção da JVM host.
 */
public class VortexJniException extends RuntimeException {

    private final transient DvmObject<?> pendingException;

    public VortexJniException(DvmObject<?> pendingException) {
        super(describe(pendingException));
        this.pendingException = pendingException;
    }

    /** O DvmObject da exceção nativa pendente. */
    public DvmObject<?> getPendingException() {
        return pendingException;
    }

    private static String describe(DvmObject<?> ex) {
        if (ex == null) {
            return "native JNI exception";
        }
        String type = ex.getObjectType() != null ? ex.getObjectType().getName() : ex.getClass().getSimpleName();
        String msg = null;
        try {
            Object v = ex.getValue();
            if (v instanceof Pointer) {
                msg = ((Pointer) v).getString(0);
            } else if (v != null) {
                msg = String.valueOf(v);
            }
        } catch (Exception ignored) {
            // valor não-legível; fica só o tipo
        }
        return msg == null ? type : (type + ": " + msg);
    }
}
