package com.vortexdbg.linux.file

import java.net.Inet4Address

open class NetworkIF {

    @JvmField
    val index: Int
    @JvmField
    val ifName: String
    @JvmField
    val ipv4: Inet4Address
    @JvmField
    val broadcast: Inet4Address?

    constructor(index: Int, ifName: String, ipv4: Inet4Address) : this(index, ifName, ipv4, null)

    constructor(index: Int, ifName: String, ipv4: Inet4Address, broadcast: Inet4Address?) {
        this.index = index
        this.ifName = getIfName(ifName)
        this.ipv4 = ipv4
        this.broadcast = broadcast
    }

    private fun getIfName(ifName: String): String {
        if ("lo0" == ifName) {
            return "lo"
        }
        if ("en0" == ifName) {
            return "wlan0"
        }
        return ifName
    }

    fun isLoopback(): Boolean {
        return ifName.startsWith("lo")
    }

    override fun toString(): String {
        return ifName
    }
}
