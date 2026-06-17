package com.vortexdbg.app

import java.io.File
import java.net.MalformedURLException
import java.net.URL
import java.net.URLClassLoader

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
open class VortexClassLoader(parent: ClassLoader?, vararg sources: File) :
    URLClassLoader(toUrls(sources), parent) {

    constructor(vararg sources: File) : this(VortexClassLoader::class.java.classLoader, *sources)

    /** Carrega uma classe do app pelo nome binário (ex.: "org.cf.crypto.XORCrypt"). */
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
