package com.vortexdbg.debugger

interface DebugServer : Debugger, Runnable {

    companion object {
        const val DEFAULT_PORT: Int = 23946

        const val PACKET_SIZE: Int = 1024

        const val IDA_PROTOCOL_VERSION_V7: Byte = 0x19 // IDA Pro v7.x
        const val IDA_DEBUGGER_ID: Byte = 0xb // armlinux

        const val DEBUG_EXEC_NAME: String = "vortexdbg"
    }

}
