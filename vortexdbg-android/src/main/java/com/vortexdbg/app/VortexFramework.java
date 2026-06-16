package com.vortexdbg.app;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Camada de framework Android para a execução das classes do app na JVM host (A1 / WF4).
 *
 * Disponibiliza as classes {@code android.*} para o código do app, usando o
 * <b>android-all</b> da Robolectric (AOSP real — não o stub {@code android.jar}).
 * Para a grande maioria das classes AOSP "sem shadow" (Base64, TextUtils, JSON,
 * coleções, crypto helpers, etc.) isso já basta — elas executam de verdade na host JVM.
 *
 * <p>Classes {@code android.*} que dependem de estado/serviços do runtime (Looper,
 * Context, lifecycle de Activity, Resources, Parcel) exigem a instrumentação + shadows
 * do Robolectric — ver {@code VortexRobolectricSandbox} / A-FINDINGS.md. Esta classe é o
 * ponto único onde a camada de framework é montada e, no futuro, trocada/estendida.
 */
public class VortexFramework {

    private final List<File> frameworkJars;

    public VortexFramework(File androidAll, File... extraFrameworkJars) {
        if (androidAll == null || !androidAll.exists()) {
            throw new IllegalArgumentException("android-all jar não encontrado: " + androidAll);
        }
        List<File> jars = new ArrayList<>();
        jars.add(androidAll);
        if (extraFrameworkJars != null) {
            for (File f : extraFrameworkJars) {
                if (f != null && f.exists()) {
                    jars.add(f);
                }
            }
        }
        this.frameworkJars = Collections.unmodifiableList(jars);
    }

    /** Conveniência: framework só com o android-all. */
    public static VortexFramework fromAndroidAll(File androidAll) {
        return new VortexFramework(androidAll);
    }

    /** Os jars que compõem a camada de framework. */
    public List<File> frameworkJars() {
        return frameworkJars;
    }

    /**
     * Cria o classloader do app já com a camada de framework no classpath — as classes
     * do app que tocam {@code android.*} resolvem contra o android-all. As fontes do app
     * vêm primeiro (precedência sobre o framework).
     */
    public VortexClassLoader newAppClassLoader(File... appSources) {
        return newAppClassLoader(VortexFramework.class.getClassLoader(), appSources);
    }

    public VortexClassLoader newAppClassLoader(ClassLoader parent, File... appSources) {
        List<File> all = new ArrayList<>();
        if (appSources != null) {
            Collections.addAll(all, appSources);
        }
        all.addAll(frameworkJars);
        return new VortexClassLoader(parent, all.toArray(new File[0]));
    }
}
