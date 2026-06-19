package com.vortexdbg.app

import java.io.File
import java.util.Collections

/**
 * Android framework layer for running the app's classes on the host JVM (A1 / WF4).
 *
 * Exposes the android.* classes to app code using Robolectric's android-all (real AOSP,
 * not the android.jar stub). For the vast majority of "shadow-less" AOSP classes (Base64,
 * TextUtils, JSON, collections, crypto helpers, etc.) this is enough — they run for real
 * on the host JVM.
 *
 * android.* classes that depend on runtime state/services (Looper, Context, Activity
 * lifecycle, Resources, Parcel) require Robolectric's instrumentation + shadows — see
 * VortexRobolectricSandbox / A-FINDINGS.md. This class is the single place where the
 * framework layer is assembled and, in the future, swapped/extended.
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

    /** The jars that make up the framework layer. */
    open fun frameworkJars(): List<File> {
        return frameworkJars
    }

    /**
     * Creates the app classloader with the framework layer already on the classpath — app
     * classes that touch android.* resolve against android-all. App sources come first, so
     * they take precedence over the framework.
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
        /** Convenience: framework with android-all only. */
        @JvmStatic
        fun fromAndroidAll(androidAll: File?): VortexFramework {
            return VortexFramework(androidAll)
        }
    }
}
