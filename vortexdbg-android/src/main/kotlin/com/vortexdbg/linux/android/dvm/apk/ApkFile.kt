package com.vortexdbg.linux.android.dvm.apk

import net.dongliu.apk.parser.bean.ApkMeta
import net.dongliu.apk.parser.bean.CertificateMeta
import net.dongliu.apk.parser.exception.ParserException

import java.io.File
import java.io.IOException
import java.security.cert.CertificateException
import java.util.ArrayList

internal class ApkFile(private val apkFile: File) : Apk {

    private var apkMeta: ApkMeta? = null

    override fun getVersionCode(): Long {
        apkMeta?.let {
            return it.getVersionCode()
        }

        try {
            net.dongliu.apk.parser.ApkFile(this.apkFile).use { apkFile ->
                apkMeta = apkFile.getApkMeta()
                return apkMeta!!.getVersionCode()
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun getVersionName(): String? {
        apkMeta?.let {
            return it.getVersionName()
        }

        try {
            net.dongliu.apk.parser.ApkFile(this.apkFile).use { apkFile ->
                apkMeta = apkFile.getApkMeta()
                return apkMeta!!.getVersionName()
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun getManifestXml(): String? {
        try {
            net.dongliu.apk.parser.ApkFile(this.apkFile).use { apkFile ->
                return apkFile.getManifestXml()
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun openAsset(fileName: String): ByteArray? {
        try {
            net.dongliu.apk.parser.ApkFile(this.apkFile).use { apkFile ->
                return apkFile.getFileData("assets/$fileName")
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    private var signatures: Array<CertificateMeta>? = null

    override fun getSignatures(): Array<CertificateMeta>? {
        signatures?.let {
            return it
        }

        try {
            net.dongliu.apk.parser.ApkFile(this.apkFile).use { apkFile ->
                val signatures: MutableList<CertificateMeta> = ArrayList(10)
                for (signer in apkFile.getApkSingers()) {
                    signatures.addAll(signer.getCertificateMetas())
                }
                this.signatures = signatures.toTypedArray()
                return this.signatures
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        } catch (e: CertificateException) {
            throw IllegalStateException(e)
        }
    }

    override fun getPackageName(): String? {
        apkMeta?.let {
            return it.getPackageName()
        }

        try {
            net.dongliu.apk.parser.ApkFile(this.apkFile).use { apkFile ->
                apkMeta = apkFile.getApkMeta()
                return apkMeta!!.getPackageName()
            }
        } catch (e: ParserException) { // Manifest file not found
            return null
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }

    override fun getParentFile(): File? {
        return apkFile.getParentFile()
    }

    override fun getFileData(path: String): ByteArray? {
        try {
            net.dongliu.apk.parser.ApkFile(this.apkFile).use { apkFile ->
                return apkFile.getFileData(path)
            }
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }
    }
}
