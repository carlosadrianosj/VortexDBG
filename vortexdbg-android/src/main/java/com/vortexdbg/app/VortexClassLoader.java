package com.vortexdbg.app;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Carregador das classes do APP rodando na JVM host (arquitetura A1).
 *
 * Recebe os artefatos `.class`/`.jar` extraídos do APK (via JEB ou dex2jar) e os
 * carrega num classloader dedicado — permitindo que o Vortex-DBG execute a lógica
 * Java real do app na JVM host (substituindo o fluxo LSPosed/Zygote num device).
 *
 * O parent padrão é o classloader do próprio Vortex, de modo que as classes do app
 * enxergam `java.*` (e, quando a camada de framework do WF4 estiver no classpath,
 * também `android.*`).
 */
public class VortexClassLoader extends URLClassLoader {

    public VortexClassLoader(ClassLoader parent, File... sources) {
        super(toUrls(sources), parent);
    }

    public VortexClassLoader(File... sources) {
        this(VortexClassLoader.class.getClassLoader(), sources);
    }

    private static URL[] toUrls(File[] sources) {
        List<URL> urls = new ArrayList<>(sources.length);
        for (File f : sources) {
            if (!f.exists()) {
                throw new IllegalArgumentException("source not found: " + f);
            }
            try {
                urls.add(f.toURI().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(f.toString(), e);
            }
        }
        return urls.toArray(new URL[0]);
    }

    /** Carrega uma classe do app pelo nome binário (ex.: "org.cf.crypto.XORCrypt"). */
    public Class<?> loadApp(String binaryName) {
        try {
            return loadClass(binaryName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("app class not found: " + binaryName, e);
        }
    }
}
