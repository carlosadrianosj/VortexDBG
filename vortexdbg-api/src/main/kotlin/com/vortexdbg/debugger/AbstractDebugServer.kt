package com.vortexdbg.debugger

import com.alibaba.fastjson.util.IOUtils
import com.vortexdbg.Emulator
import com.vortexdbg.arm.AbstractARMDebugger
import com.vortexdbg.utils.Inspector
import keystone.Keystone
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.LinkedList
import java.util.Scanner
import java.util.concurrent.Semaphore

abstract class AbstractDebugServer(emulator: Emulator<*>) : AbstractARMDebugger(emulator), DebugServer {

    private val pendingWrites: MutableList<ByteBuffer> = LinkedList()

    private var selector: Selector? = null
    private var serverSocketChannel: ServerSocketChannel? = null
    private var socketChannel: SocketChannel? = null
    private val input: ByteBuffer = ByteBuffer.allocate(DebugServer.PACKET_SIZE)

    init {
        setSingleStep(1) // break at attach

        val thread = Thread(this, "dbgserver")
        thread.start()
    }

    private var serverShutdown = false
    private var closeConnection = false
    private var serverRunning = false

    protected fun isDebuggerConnected(): Boolean {
        return socketChannel != null
    }

    final override fun run() {
        runServer()
    }

    private fun runServer() {
        selector = null
        serverSocketChannel = null
        socketChannel = null
        try {
            serverSocketChannel = ServerSocketChannel.open()
            serverSocketChannel!!.configureBlocking(false)

            serverSocketChannel!!.socket().bind(InetSocketAddress(DebugServer.DEFAULT_PORT))

            selector = Selector.open()
            serverSocketChannel!!.register(selector, SelectionKey.OP_ACCEPT)
        } catch (ex: IOException) {
            throw IllegalStateException(ex)
        }

        serverShutdown = false
        serverRunning = true

        System.err.println("Start " + this + " server on port: " + DebugServer.DEFAULT_PORT)
        onServerStart()

        while (serverRunning) {
            try {
                val count = selector!!.select(50)
                if (count <= 0) {
                    if (!isDebuggerConnected() && System.`in`.available() > 0) {
                        val line = Scanner(System.`in`).nextLine()
                        if ("c" == line) {
                            serverRunning = false
                            break
                        } else {
                            println("c: continue")
                        }
                    }
                    continue
                }

                val selectedKeys = selector!!.selectedKeys().iterator()
                while (selectedKeys.hasNext()) {
                    val key = selectedKeys.next()
                    if (key.isValid) {
                        if (key.isAcceptable) {
                            onSelectAccept(key)
                        }
                        if (key.isReadable) {
                            onSelectRead(key)
                        }
                        if (key.isWritable) {
                            onSelectWrite(key)
                        }
                    }
                    selectedKeys.remove()
                }

                processInput(input)
            } catch (e: Throwable) {
                if (log.isDebugEnabled) {
                    log.debug("run server ex", e)
                }
            }
        }

        com.alibaba.fastjson.util.IOUtils.close(serverSocketChannel)
        serverSocketChannel = null
        com.alibaba.fastjson.util.IOUtils.close(selector)
        selector = null
        closeSocketChannel()
        resumeRun()
    }

    protected abstract fun onServerStart()

    protected abstract fun processInput(input: ByteBuffer)

    private fun enableNewConnections(enable: Boolean) {
        if (serverSocketChannel == null) {
            return
        }
        val key = serverSocketChannel!!.keyFor(selector)
        key.interestOps(if (enable) SelectionKey.OP_ACCEPT else 0)
    }

    @Throws(IOException::class)
    private fun onSelectAccept(key: SelectionKey) {
        val ssc = key.channel() as ServerSocketChannel
        val sc = ssc.accept()
        if (sc != null) {
            closeConnection = false
            pendingWrites.clear()
            input.clear()
            sc.configureBlocking(false)
            sc.register(key.selector(), SelectionKey.OP_READ)
            socketChannel = sc
            enableNewConnections(false)
            onDebuggerConnected()
        }
    }

    protected abstract fun onDebuggerConnected()

    @Throws(IOException::class)
    private fun onSelectWrite(key: SelectionKey) {
        val sc = key.channel() as SocketChannel
        if (pendingWrites.isEmpty() && closeConnection) {
            closeSocketChannel()
            return
        }

        while (!pendingWrites.isEmpty()) {
            val bb = pendingWrites[0]
            try {
                sc.write(bb)
            } catch (ex: IOException) {
                closeSocketChannel()
                throw ex
            }
            if (bb.remaining() > 0) {
                break
            }
            pendingWrites.removeAt(0)
        }

        if (pendingWrites.isEmpty() && !closeConnection) {
            enableWrites(false)
        }
    }

    private fun onSelectRead(key: SelectionKey) {
        val sc = key.channel() as SocketChannel

        var numRead: Int
        try {
            numRead = sc.read(input)
        } catch (ex: IOException) {
            numRead = -1
        }

        if (numRead == -1) {
            closeSocketChannel()
        }
    }

    private fun closeSocketChannel() {
        if (socketChannel == null) {
            return
        }
        val key = socketChannel!!.keyFor(selector)
        if (key != null) key.cancel()
        IOUtils.close(socketChannel)
        socketChannel = null
        if (!serverShutdown) {
            enableNewConnections(true)
        } else {
            serverRunning = false
        }
    }

    private fun enableWrites(enable: Boolean) {
        if (socketChannel == null) {
            return
        }
        val key = socketChannel!!.keyFor(selector)
        key.interestOps(if (enable) SelectionKey.OP_WRITE else SelectionKey.OP_READ)
    }

    protected fun sendData(data: ByteArray) {
        if (log.isDebugEnabled) {
            log.debug(Inspector.inspectString(data, "sendData"))
        }
        val bb = ByteBuffer.wrap(data)
        pendingWrites.add(bb)
        enableWrites(true)
    }

    private var semaphore: Semaphore? = null

    @Throws(Exception::class)
    protected final override fun loop(emulator: Emulator<*>, address: Long, size: Int, runnable: DebugRunnable<*>?) {
        if (address <= 0) {
            return
        }

        semaphore = Semaphore(0)

        onHitBreakPoint(emulator, address)
        semaphore!!.acquire()
    }

    override fun <T> run(runnable: DebugRunnable<T>?): T {
        throw UnsupportedOperationException()
    }

    protected abstract fun onHitBreakPoint(emulator: Emulator<*>, address: Long)

    fun resumeRun() {
        if (semaphore != null) {
            semaphore!!.release()
        }
    }

    fun singleStep() {
        setSingleStep(1)
        resumeRun()
    }

    final override fun close() {
        super.close()

        if (onDebuggerExit()) {
            shutdownServer()
        }
    }

    protected abstract fun onDebuggerExit(): Boolean

    fun shutdownServer() {
        serverShutdown = true
        closeConnection = true
        enableWrites(true)
    }

    fun detachServer() {
        closeConnection = true
        enableWrites(true)
    }

    protected override fun createKeystone(isThumb: Boolean): Keystone {
        throw UnsupportedOperationException()
    }

    protected override fun resolveRegister(command: String, nameOut: Array<String?>): Int {
        throw UnsupportedOperationException()
    }

    protected override fun resolveWriteRegister(command: String): Int {
        throw UnsupportedOperationException()
    }

    protected override fun showWriteRegs(reg: Int) {
        throw UnsupportedOperationException()
    }

    protected override fun showWriteHelp() {
        throw UnsupportedOperationException()
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AbstractDebugServer::class.java)
    }
}
