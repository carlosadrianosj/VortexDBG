package com.vortexdbg.mcp

import com.alibaba.fastjson.JSONObject
import com.sun.net.httpserver.HttpExchange
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

open class McpSession {

    private val sessionId: String = UUID.randomUUID().toString()

    @Volatile
    private var sseOutput: OutputStream? = null

    @Volatile
    private var closed: Boolean = false

    fun getSessionId(): String {
        return sessionId
    }

    @Throws(IOException::class)
    fun attachSseStream(exchange: HttpExchange) {
        exchange.responseHeaders.set("Content-Type", "text/event-stream")
        exchange.responseHeaders.set("Cache-Control", "no-cache")
        exchange.responseHeaders.set("Connection", "keep-alive")
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(200, 0)
        this.sseOutput = exchange.responseBody
        this.closed = false
    }

    fun sendEndpointEvent(baseUrl: String) {
        sendSseEvent("endpoint", "$baseUrl/message?sessionId=$sessionId")
    }

    fun sendJsonRpcResponse(response: JSONObject) {
        sendSseEvent("message", response.toJSONString())
    }

    fun sendNotification(notification: JSONObject) {
        sendSseEvent("message", notification.toJSONString())
    }

    @Synchronized
    private fun sendSseEvent(event: String, data: String) {
        if (closed || sseOutput == null) {
            return
        }
        try {
            val payload = "event: $event\ndata: $data\n\n"
            sseOutput!!.write(payload.toByteArray(StandardCharsets.UTF_8))
            sseOutput!!.flush()
        } catch (e: IOException) {
            log.debug("SSE write failed, closing session {}", sessionId, e)
            close()
        }
    }

    @Synchronized
    fun close() {
        if (closed) {
            return
        }
        closed = true
        if (sseOutput != null) {
            try {
                sseOutput!!.close()
            } catch (ignored: IOException) {
            }
            sseOutput = null
        }
    }

    fun isClosed(): Boolean {
        return closed
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(McpSession::class.java)
    }
}
