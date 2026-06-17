package com.vortexdbg.linux.android.dvm.apk

import net.dongliu.apk.parser.bean.CertificateMeta

import java.io.File

interface Apk {

    fun getVersionCode(): Long

    fun getVersionName(): String?

    fun getManifestXml(): String?

    fun openAsset(fileName: String): ByteArray?

    fun getSignatures(): Array<CertificateMeta>?

    fun getPackageName(): String?

    fun getParentFile(): File?

    fun getFileData(path: String): ByteArray?

}
