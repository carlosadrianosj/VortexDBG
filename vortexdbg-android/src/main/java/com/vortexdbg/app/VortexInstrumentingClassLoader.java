package com.vortexdbg.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * E (Vortex-DBG / A1) — classloader que carrega as classes-alvo do app (child-first) e
 * instrumenta seus métodos {@code native} via {@link VortexNativeInstrumentor}, roteando-os
 * ao UniDBG. As demais classes delegam ao parent.
 */
public class VortexInstrumentingClassLoader extends ClassLoader {

    private final String targetPrefix;

    public VortexInstrumentingClassLoader(ClassLoader parent, String targetPrefix) {
        super(parent);
        this.targetPrefix = targetPrefix;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(targetPrefix)) {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = defineInstrumented(name);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
        return super.loadClass(name, resolve);
    }

    private Class<?> defineInstrumented(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        try (InputStream in = getParent().getResourceAsStream(path)) {
            if (in == null) {
                throw new ClassNotFoundException(name);
            }
            byte[] original = readAll(in);
            byte[] instrumented = VortexNativeInstrumentor.instrument(original);
            return defineClass(name, instrumented, 0, instrumented.length);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
