package com.vortexdbg.app

import java.io.File
import java.util.Collections

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
open class VortexFramework(androidAll: File?, vararg extraFrameworkJars: File?) {

    private val frameworkJars: List<File>

    init {
        if (androidAll == null || !androidAll.exists()) {
            throw IllegalArgumentException("android-all jar não encontrado: $androidAll")
        }
        val jars = ArrayList<File>()
        jars.add(androidAll)
        for (f in extraFrameworkJars) {
            if (f != null && f.exists()) {
                jars.add(f)
            }
        }
        this.frameworkJars = Collections.unmodifiableList(jars)
    }

    /** Os jars que compõem a camada de framework. */
    open fun frameworkJars(): List<File> {
        return frameworkJars
    }

    /**
     * Cria o classloader do app já com a camada de framework no classpath — as classes
     * do app que tocam {@code android.*} resolvem contra o android-all. As fontes do app
     * vêm primeiro (precedência sobre o framework).
     */
    open fun newAppClassLoader(vararg appSources: File): VortexClassLoader {
        return newAppClassLoader(VortexFramework::class.java.classLoader, *appSources)
    }

    open fun newAppClassLoader(parent: ClassLoader?, vararg appSources: File): VortexClassLoader {
        val all = ArrayList<File>()
        Collections.addAll(all, *appSources)
        all.addAll(frameworkJars)
        return VortexClassLoader(parent, *all.toTypedArray())
    }

    companion object {
        /** Conveniência: framework só com o android-all. */
        @JvmStatic
        fun fromAndroidAll(androidAll: File?): VortexFramework {
            return VortexFramework(androidAll)
        }
    }
}
