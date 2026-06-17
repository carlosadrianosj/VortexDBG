package com.vortexdbg.linux.android.dvm.apk

interface AssetResolver {

    fun resolveAsset(fileName: String): ByteArray?

}
