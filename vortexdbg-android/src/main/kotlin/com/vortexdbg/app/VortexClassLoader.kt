package com.vortexdbg.app

import java.io.File
import java.net.MalformedURLException
import java.net.URL
import java.net.URLClassLoader

/**
 * Loader for the APP's classes running on the host JVM (A1 architecture).
 *
 * Takes the .class/.jar artifacts extracted from the APK (via JEB or dex2jar) and loads
 * them in a dedicated classloader — letting Vortex-DBG run the app's real Java logic on
 * the host JVM (replacing the LSPosed/Zygote flow on a device).
 *
 * The default parent is Vortex's own classloader, so app classes can see java.* (and,
 * once the WF4 framework layer is on the classpath, android.* too).
 */
open class VortexClassLoader(parent: ClassLoader?, vararg sources: File) :
    URLClassLoader(toUrls(sources), parent) {

    constructor(vararg sources: File) : this(VortexClassLoader::class.java.classLoader, *sources)

    /** Loads an app class by binary name (e.g. "org.cf.crypto.XORCrypt"). */
    open fun loadApp(binaryName: String): Class<*> {
        try {
            return loadClass(binaryName)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("app class not found: $binaryName", e)
        }
    }

    companion object {
        private fun toUrls(sources: Array<out File>): Array<URL> {
            val urls = ArrayList<URL>(sources.size)
            for (f in sources) {
                if (!f.exists()) {
                    throw IllegalArgumentException("source not found: $f")
                }
                try {
                    urls.add(f.toURI().toURL())
                } catch (e: MalformedURLException) {
                    throw IllegalArgumentException(f.toString(), e)
                }
            }
            return urls.toTypedArray()
        }
    }
}
