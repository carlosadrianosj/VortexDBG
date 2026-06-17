package com.vortexdbg.linux.android.dvm.api

import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM
import net.dongliu.apk.parser.bean.CertificateMeta
import org.apache.commons.codec.binary.Hex

import java.util.Arrays

open class Signature(vm: VM, meta: CertificateMeta) : DvmObject<CertificateMeta>(vm.resolveClass("android/content/pm/Signature"), meta) {

    open fun getHashCode(): Int {
        return Arrays.hashCode(value.getData())
    }

    open fun toByteArray(): ByteArray {
        return value.getData()
    }

    open fun toCharsString(): String {
        return Hex.encodeHexString(value.getData())
    }

}
