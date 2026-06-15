package com.vortexdbg.app;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Invocação estilo LSPosed das classes do app — porém OFF-DEVICE, na JVM host.
 *
 * Em vez de injetar no Zygote e chamar classes num device físico, o analista usa o
 * VortexInvoker para carregar a classe do app (via {@link VortexClassLoader}) e
 * invocar um método com argumentos arbitrários por reflexão, recebendo o valor de
 * retorno real. É o núcleo do caso de uso da arquitetura A1.
 */
public class VortexInvoker {

    private final ClassLoader classLoader;

    public VortexInvoker(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public Class<?> load(String className) throws ClassNotFoundException {
        return Class.forName(className, true, classLoader);
    }

    /** Invoca um método estático com tipos de parâmetro explícitos. */
    public Object invokeStatic(String className, String method, Class<?>[] paramTypes, Object... args) throws Exception {
        Class<?> c = load(className);
        Method m = findMethod(c, method, paramTypes);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    /** Invoca um método de instância com tipos de parâmetro explícitos. */
    public Object invoke(Object target, String method, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = findMethod(target.getClass(), method, paramTypes);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    /** Procura o método na classe e na hierarquia de superclasses. */
    private static Method findMethod(Class<?> c, String method, Class<?>[] paramTypes) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod(method, paramTypes);
            } catch (NoSuchMethodException ignored) {
                // sobe na hierarquia
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + method);
    }

    private static Exception unwrap(InvocationTargetException e) {
        Throwable cause = e.getTargetException();
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return e;
    }
}
