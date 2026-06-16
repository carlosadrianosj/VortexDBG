package com.vortexdbg.utils

import java.io.File
import java.net.URISyntaxException
import java.net.URL

object ResourceUtils {

    @JvmStatic
    fun toFile(url: URL): File? {
        val protocol = url.protocol
        if ("file" == protocol) {
            try {
                return File(url.toURI())
            } catch (e: URISyntaxException) {
                throw IllegalStateException(url.toString(), e)
            }
        }
        return null
    }

}
