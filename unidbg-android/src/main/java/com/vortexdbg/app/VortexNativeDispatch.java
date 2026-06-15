package com.vortexdbg.app;

import com.vortexdbg.AndroidEmulator;
import com.vortexdbg.linux.android.dvm.DvmClass;
import com.vortexdbg.linux.android.dvm.DvmObject;

/**
 * E (Vortex-DBG / A1) — a "outra metade" da fusão: roteamento Java(host) → native(emulado).
 *
 * Métodos {@code native} das classes do app, reescritos por {@link VortexNativeInstrumentor},
 * deixam de ser nativos e passam a chamar estes dispatchers, que executam a função nativa
 * correspondente DENTRO do UniDBG (a `.so` emulada) e devolvem o resultado ao host.
 *
 * A sessão ativa é thread-local — instale com {@link #setSession(VortexSession)} antes de
 * invocar os métodos instrumentados.
 */
public final class VortexNativeDispatch {

    private static final ThreadLocal<VortexSession> CURRENT = new ThreadLocal<>();

    private VortexNativeDispatch() {}

    public static void setSession(VortexSession session) { CURRENT.set(session); }
    public static void clearSession() { CURRENT.remove(); }

    private static VortexSession session() {
        VortexSession s = CURRENT.get();
        if (s == null) {
            throw new IllegalStateException("nenhuma VortexSession ativa nesta thread "
                    + "(chame VortexNativeDispatch.setSession antes de invocar métodos nativos do app)");
        }
        return s;
    }

    private static DvmClass dvmClass(String className) {
        return session().resolveNativeClass(className.replace('.', '/'));
    }

    private static AndroidEmulator emulator() {
        return session().emulator();
    }

    // ---- dispatchers por tipo de retorno (a assinatura JNI = method + descriptor) ----

    public static void dispatchVoid(String className, String method, String descriptor, Object[] args) {
        dvmClass(className).callStaticJniMethod(emulator(), method + descriptor, args);
    }

    public static boolean dispatchBoolean(String className, String method, String descriptor, Object[] args) {
        return dvmClass(className).callStaticJniMethodBoolean(emulator(), method + descriptor, args);
    }

    public static int dispatchInt(String className, String method, String descriptor, Object[] args) {
        return dvmClass(className).callStaticJniMethodInt(emulator(), method + descriptor, args);
    }

    public static long dispatchLong(String className, String method, String descriptor, Object[] args) {
        return dvmClass(className).callStaticJniMethodLong(emulator(), method + descriptor, args);
    }

    public static Object dispatchObject(String className, String method, String descriptor, Object[] args) {
        DvmObject<?> r = dvmClass(className).callStaticJniMethodObject(emulator(), method + descriptor, args);
        return r == null ? null : r.getValue();
    }
}
