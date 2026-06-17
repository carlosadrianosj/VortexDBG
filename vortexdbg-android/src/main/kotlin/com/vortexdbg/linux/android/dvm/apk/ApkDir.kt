package com.vortexdbg.linux.android.dvm.apk

import net.dongliu.apk.parser.bean.ApkMeta
import net.dongliu.apk.parser.bean.ApkSigner
import net.dongliu.apk.parser.bean.CertificateMeta
import net.dongliu.apk.parser.parser.ApkMetaTranslator
import net.dongliu.apk.parser.parser.BinaryXmlParser
import net.dongliu.apk.parser.parser.CertificateParser
import net.dongliu.apk.parser.parser.CompositeXmlStreamer
import net.dongliu.apk.parser.parser.XmlStreamer
import net.dongliu.apk.parser.parser.XmlTranslator
import net.dongliu.apk.parser.struct.AndroidConstants
import net.dongliu.apk.parser.struct.resource.ResourceTable
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils

import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.nio.ByteBuffer
import java.security.cert.CertificateException
import java.util.ArrayList
import java.util.Locale

internal class ApkDir(private val dir: File) : Apk {

    override fun getVersionCode(): Long {
        parseManifest()
        return if (apkMeta == null) 0L else apkMeta!!.getVersionCode()
    }

    override fun getVersionName(): String? {
        parseManifest()
        return if (apkMeta == null) null else apkMeta!!.getVersionName()
    }

    override fun getManifestXml(): String? {
        parseManifest()
        return manifestXml
    }

    override fun openAsset(fileName: String): ByteArray? {
        return getFileData("assets/$fileName")
    }

    override fun getFileData(path: String): ByteArray? {
        val file = File(dir, path)
        return if (file.canRead()) {
            try {
                FileUtils.readFileToByteArray(file)
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
        } else {
            null
        }
    }

    private var signatures: Array<CertificateMeta>? = null

    override fun getSignatures(): Array<CertificateMeta>? {
        if (signatures == null) {
            try {
                parseCertificates()
            } catch (e: IOException) {
                throw IllegalStateException(e)
            } catch (e: CertificateException) {
                throw IllegalStateException(e)
            }
        }
        return this.signatures
    }

    private class CertificateFile(val path: String, val data: ByteArray)

    @Throws(IOException::class)
    private fun getAllCertificateData(): List<CertificateFile> {
        val list: MutableList<CertificateFile> = ArrayList()
        scanCertificateFile(list, dir)
        return list
    }

    @Throws(IOException::class)
    private fun scanCertificateFile(list: MutableList<CertificateFile>, dir: File) {
        val files = dir.listFiles(object : FileFilter {
            override fun accept(pathname: File): Boolean {
                if (pathname.isDirectory) return true
                val ext = FilenameUtils.getExtension(pathname.name)
                return ext.equals("RSA", ignoreCase = true) || ext.equals("DSA", ignoreCase = true)
            }
        })
        if (files != null) {
            for (file in files) {
                if (file.isDirectory) {
                    scanCertificateFile(list, file)
                } else {
                    list.add(CertificateFile(file.path, FileUtils.readFileToByteArray(file)))
                }
            }
        }
    }

    @Throws(IOException::class, CertificateException::class)
    private fun parseCertificates() {
        val apkSigners: MutableList<ApkSigner> = ArrayList()
        for (file in getAllCertificateData()) {
            val parser = CertificateParser.getInstance(file.data)
            val certificateMetas = parser.parse()
            apkSigners.add(ApkSigner(file.path, certificateMetas))
        }
        val signatures: MutableList<CertificateMeta> = ArrayList(apkSigners.size)
        for (signer in apkSigners) {
            signatures.addAll(signer.getCertificateMetas())
        }
        this.signatures = signatures.toTypedArray()
    }

    override fun getPackageName(): String? {
        parseManifest()
        return if (apkMeta == null) null else apkMeta!!.getPackageName()
    }

    override fun getParentFile(): File? {
        return dir.getParentFile()
    }

    private var manifestParsed = false

    private var manifestXml: String? = null
    private var apkMeta: ApkMeta? = null

    private fun parseManifest() {
        if (manifestParsed) {
            return
        }
        val resourceTable = ResourceTable()
        val preferredLocale = Locale.US
        val xmlTranslator = XmlTranslator()
        val apkTranslator = ApkMetaTranslator(resourceTable, preferredLocale)
        val xmlStreamer: XmlStreamer = CompositeXmlStreamer(xmlTranslator, apkTranslator)

        val data = getFileData(AndroidConstants.MANIFEST_FILE)
        if (data != null) {
            transBinaryXml(data, xmlStreamer, resourceTable, preferredLocale)
            this.manifestXml = xmlTranslator.getXml()
            this.apkMeta = apkTranslator.getApkMeta()
            manifestParsed = true
        }
    }

    private fun transBinaryXml(data: ByteArray, xmlStreamer: XmlStreamer, resourceTable: ResourceTable, preferredLocale: Locale) {
        val buffer = ByteBuffer.wrap(data)
        val binaryXmlParser = BinaryXmlParser(buffer, resourceTable)
        binaryXmlParser.setLocale(preferredLocale)
        binaryXmlParser.setXmlStreamer(xmlStreamer)
        binaryXmlParser.parse()
    }

}
