package com.vortexdbg.app;

import com.vortexdbg.Emulator;
import com.vortexdbg.Module;
import com.vortexdbg.arm.context.RegisterContext;
import com.vortexdbg.debugger.Debugger;
import com.vortexdbg.linux.android.dvm.DvmMethod;
import com.vortexdbg.linux.android.dvm.Jni;
import com.vortexdbg.unwind.Frame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * G (Vortex-DBG / A1) — Debugger dual-layer.
 *
 * Unifica numa visão só as DUAS camadas:
 *   - NATIVO: reusa o debugger ARM do UniDBG ({@code emulator.attach()}) — breakpoints por
 *     símbolo/endereço, registradores e backtrace nativo (via {@code Unwinder}).
 *   - JAVA / fronteira JNI: envolve o {@link Jni} para observar cada cruzamento
 *     native→java (qualquer {@code call*Method}), permitindo "seguir" a execução quando
 *     ela atravessa a ponte e montar uma pilha unificada (frames nativos + método Java).
 *
 * Modo não-interativo (callbacks) — base para um frontend interativo (REPL/IDE) depois.
 */
public class VortexDebugger {

    private final Emulator<?> emulator;
    private final Debugger nativeDebugger;

    public VortexDebugger(Emulator<?> emulator) {
        this.emulator = emulator;
        this.nativeDebugger = emulator.attach();
    }

    public Debugger nativeDebugger() {
        return nativeDebugger;
    }

    // ---------- breakpoints nativos ----------

    public interface NativeBreakHandler {
        void onBreak(NativeBreakContext ctx);
    }

    public void breakNative(Module module, String symbol, final NativeBreakHandler handler) {
        nativeDebugger.addBreakPoint(module, symbol, (emu, address) -> {
            handler.onBreak(new NativeBreakContext(address));
            return true; // tratado -> continua (não entra no debugger interativo)
        });
    }

    public void breakNative(long address, final NativeBreakHandler handler) {
        nativeDebugger.addBreakPoint(address, (emu, addr) -> {
            handler.onBreak(new NativeBreakContext(addr));
            return true;
        });
    }

    // ---------- observação do seam JNI (native -> java) ----------

    public interface JavaCrossHandler {
        void onCross(String signature, Object[] rawArgs);
    }

    /**
     * Envolve um {@link Jni} base disparando {@code handler} a cada chamada native→java
     * (todo método {@code call*Method}). Instale com {@code vm.setJni(debugger.instrument(jni, h))}.
     */
    public Jni instrument(final Jni base, final JavaCrossHandler handler) {
        return (Jni) Proxy.newProxyInstance(
                Jni.class.getClassLoader(),
                new Class[]{Jni.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getName().startsWith("call") && args != null && args.length >= 3) {
                            handler.onCross(signatureOf(args[2]), args);
                        }
                        try {
                            return method.invoke(base, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    }
                });
    }

    // ---------- pilha / backtrace nativo ----------

    public List<String> nativeStack(int maxDepth) {
        List<String> out = new ArrayList<>();
        try {
            for (Frame f : emulator.getUnwinder().getFrames(maxDepth)) {
                out.add(f.ip == null ? "?" : String.valueOf(f.ip));
            }
        } catch (Throwable t) {
            out.add("<unwind indisponível: " + t.getClass().getSimpleName() + ">");
        }
        return out;
    }

    private static String signatureOf(Object arg) {
        if (arg instanceof String) {
            return (String) arg;
        }
        if (arg instanceof DvmMethod) {
            return String.valueOf(arg);
        }
        return String.valueOf(arg);
    }

    /** Contexto de um breakpoint nativo: PC, registradores, args e backtrace. */
    public final class NativeBreakContext {
        private final long address;

        NativeBreakContext(long address) {
            this.address = address;
        }

        public long pc() {
            return address;
        }

        public RegisterContext regs() {
            return emulator.getContext();
        }

        /** Argumento i da convenção de chamada (X0/R0 = i 0). */
        public long arg(int i) {
            return emulator.getContext().getLongArg(i);
        }

        public List<String> nativeStack(int maxDepth) {
            return VortexDebugger.this.nativeStack(maxDepth);
        }
    }
}
