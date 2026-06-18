package com.vortexdbg.mcp

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import com.vortexdbg.Emulator
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.apache.commons.io.IOUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

open class McpServer(emulator: Emulator<*>, private val port: Int) {

    private val mcpTools: McpTools = McpTools(emulator, this)
    private val sessions: MutableMap<String, McpSession> = ConcurrentHashMap()
    private var httpServer: HttpServer? = null
    private var executor: ExecutorService? = null
    private var commandPipe: PipedOutputStream? = null
    private var originalIn: InputStream? = null

    @Volatile
    private var debugIdle: Boolean = false

    private val eventQueue: BlockingQueue<JSONObject> = LinkedBlockingQueue()

    fun getPort(): Int {
        return port
    }

    fun addCustomTool(name: String, description: String, vararg paramNames: String) {
        mcpTools.addCustomTool(name, description, *paramNames)
    }

    private val toolProviders: MutableList<McpToolProvider> = ArrayList()

    /** Register an out-of-module tool provider (e.g. the Dalvik/DEX tools). */
    fun addToolProvider(provider: McpToolProvider) {
        toolProviders.add(provider)
    }

    fun getToolProviders(): List<McpToolProvider> {
        return toolProviders
    }

    @Throws(IOException::class)
    fun start() {
        commandPipe = PipedOutputStream()
        val pipedIn = PipedInputStream(commandPipe, 4096)

        originalIn = System.`in`
        System.setIn(MergedInputStream(originalIn!!, pipedIn))

        val daemonFactory = ThreadFactory { r ->
            val t = Thread(r)
            t.isDaemon = true
            t.name = "mcp-server"
            t
        }
        executor = Executors.newCachedThreadPool(daemonFactory)
        httpServer = HttpServer.create(InetSocketAddress(port), 0)
        httpServer!!.executor = executor
        httpServer!!.createContext("/sse") { exchange -> handleSse(exchange) }
        httpServer!!.createContext("/message") { exchange -> handleMessage(exchange) }
        httpServer!!.start()
    }

    fun stop() {
        for (session in sessions.values) {
            session.close()
        }
        sessions.clear()
        if (httpServer != null) {
            httpServer!!.stop(0)
            httpServer = null
        }
        if (executor != null) {
            executor!!.shutdownNow()
            executor = null
        }
        if (commandPipe != null) {
            try {
                commandPipe!!.close()
            } catch (ignored: IOException) {
            }
            commandPipe = null
        }
        if (originalIn != null) {
            System.setIn(originalIn)
            originalIn = null
        }
    }

    fun injectCommand(command: String) {
        if (commandPipe != null) {
            try {
                commandPipe!!.write((command + "\n").toByteArray(StandardCharsets.UTF_8))
                commandPipe!!.flush()
            } catch (e: IOException) {
                log.warn("Failed to inject command: {}", command, e)
            }
        }
    }

    private val operationLock = Any()

    @Volatile
    private var pendingOperation: Callable<JSONObject>? = null

    @Volatile
    private var pendingResult: JSONObject? = null

    /**
     * Execute an operation on the debugger thread (required for backend operations like hardware breakpoints).
     * Blocks the calling thread until the debugger thread completes the operation.
     */
    fun runOnDebuggerThread(operation: Callable<JSONObject>): JSONObject {
        synchronized(operationLock) {
            pendingOperation = operation
            pendingResult = null
            injectCommand("_mcp")
            try {
                val deadline = System.currentTimeMillis() + 5000
                while (pendingResult == null) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) {
                        pendingOperation = null
                        return McpTools.errorResult("Operation timed out waiting for debugger thread")
                    }
                    (operationLock as Object).wait(remaining)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                pendingOperation = null
                return McpTools.errorResult("Operation interrupted")
            }
            return pendingResult!!
        }
    }

    fun executePendingOperation() {
        synchronized(operationLock) {
            if (pendingOperation != null) {
                try {
                    pendingResult = pendingOperation!!.call()
                } catch (e: Exception) {
                    pendingResult = McpTools.errorResult("Operation failed: " + e.message)
                }
                pendingOperation = null
                (operationLock as Object).notifyAll()
            }
        }
    }

    fun queueEvent(event: JSONObject) {
        if (!eventQueue.offer(event)) {
            throw IllegalStateException()
        }
    }

    fun getPendingEventCount(): Int {
        return eventQueue.size
    }

    fun pollEvents(timeoutMs: Long): List<JSONObject> {
        val events: MutableList<JSONObject> = ArrayList()
        eventQueue.drainTo(events)
        if (!events.isEmpty() || timeoutMs <= 0) {
            return events
        }
        try {
            val first = eventQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (first != null) {
                events.add(first)
                eventQueue.drainTo(events)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return events
    }

    fun broadcastNotification(event: String, data: JSONObject) {
        val notification = JSONObject()
        notification.put("jsonrpc", "2.0")
        notification.put("method", "notifications/message")

        val params = JSONObject()
        params.put("level", "info")
        params.put("logger", "vortexdbg")
        data.put("event", event)
        params.put("data", data.toJSONString())
        notification.put("params", params)

        sessions.entries.removeIf { entry -> entry.value.isClosed() }
        for (session in sessions.values) {
            session.sendNotification(notification)
        }
    }

    @Throws(IOException::class)
    private fun handleSse(exchange: HttpExchange) {
        val method = exchange.requestMethod
        log.debug("[MCP] /sse {} from {} headers={}", method, exchange.remoteAddress,
                exchange.requestHeaders.entries)
        if ("POST" == method) {
            handleStreamableHttp(exchange)
            return
        }
        if ("OPTIONS" == method) {
            log.debug("[MCP] /sse OPTIONS preflight")
            exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Mcp-Session-Id")
            exchange.sendResponseHeaders(204, -1)
            return
        }
        if ("GET" != method) {
            log.debug("[MCP] /sse rejected method: {}", method)
            sendErrorResponse(exchange, 405, "Method Not Allowed")
            return
        }

        val session = McpSession()
        sessions[session.getSessionId()] = session
        log.debug("[MCP] SSE session created: {}", session.getSessionId())

        session.attachSseStream(exchange)
        session.sendEndpointEvent("http://localhost:$port")

        while (!session.isClosed()) {
            try {
                TimeUnit.SECONDS.sleep(1)
            } catch (e: InterruptedException) {
                break
            }
        }
        log.debug("[MCP] SSE session closed: {}", session.getSessionId())
        sessions.remove(session.getSessionId())
    }

    @Throws(IOException::class)
    private fun handleStreamableHttp(exchange: HttpExchange) {
        val body = IOUtils.toString(exchange.requestBody, StandardCharsets.UTF_8)
        log.debug("[MCP] /sse POST body: {}", body)
        val request: JSONObject?
        try {
            request = JSON.parseObject(body)
        } catch (e: Exception) {
            log.warn("[MCP] Invalid JSON in /sse POST: {}", e.message)
            sendErrorResponse(exchange, 400, "Invalid JSON: " + e.message)
            return
        }
        if (request == null) {
            sendErrorResponse(exchange, 400, "Empty JSON body")
            return
        }

        val rpcMethod = request.getString("method")
        val id = request.get("id")

        if (rpcMethod != null && rpcMethod.startsWith("notifications/")) {
            log.debug("[MCP] notification: {}", rpcMethod)
            exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
            return
        }

        val response = buildJsonRpcResponse(id, rpcMethod, request.getJSONObject("params"))

        val responseBytes = response.toJSONString().toByteArray(StandardCharsets.UTF_8)
        log.debug("[MCP] /sse POST response ({}): {} bytes", rpcMethod, responseBytes.size)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
        exchange.responseBody.write(responseBytes)
        exchange.close()
    }

    @Throws(IOException::class)
    private fun handleMessage(exchange: HttpExchange) {
        if ("OPTIONS" == exchange.requestMethod) {
            exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.set("Access-Control-Allow-Methods", "POST, OPTIONS")
            exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type")
            exchange.sendResponseHeaders(204, -1)
            return
        }

        if ("POST" != exchange.requestMethod) {
            sendErrorResponse(exchange, 405, "Method Not Allowed")
            return
        }

        val query = exchange.requestURI.query
        var sessionId: String? = null
        if (query != null) {
            for (param in query.split("&")) {
                if (param.startsWith("sessionId=")) {
                    sessionId = param.substring("sessionId=".length)
                    break
                }
            }
        }

        val session = if (sessionId != null) sessions[sessionId] else null
        if (session == null) {
            sendErrorResponse(exchange, 400, "Invalid session")
            return
        }

        val body = IOUtils.toString(exchange.requestBody, StandardCharsets.UTF_8)
        val request: JSONObject?
        try {
            request = JSON.parseObject(body)
        } catch (e: Exception) {
            log.warn("[MCP] Invalid JSON in /message: {}", e.message)
            sendErrorResponse(exchange, 400, "Invalid JSON: " + e.message)
            return
        }
        if (request == null) {
            sendErrorResponse(exchange, 400, "Empty JSON body")
            return
        }

        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(202, -1)
        exchange.close()

        val method = request.getString("method")
        val id = request.get("id")

        if ("notifications/initialized" == method || (method != null && method.startsWith("notifications/"))) {
            return
        }

        val response = buildJsonRpcResponse(id, method, request.getJSONObject("params"))

        session.sendJsonRpcResponse(response)
    }

    private fun buildJsonRpcResponse(id: Any?, method: String?, params: JSONObject?): JSONObject {
        val response = JSONObject()
        response.put("jsonrpc", "2.0")
        response.put("id", id)
        try {
            val result = dispatch(method, params)
            response.put("result", result)
        } catch (e: Exception) {
            val error = JSONObject()
            error.put("code", -32603)
            error.put("message", e.message)
            response.put("error", error)
        }
        return response
    }

    private fun dispatch(method: String?, params: JSONObject?): JSONObject {
        if ("initialize" == method) {
            return handleInitialize()
        }
        if ("tools/list" == method) {
            return handleToolsList()
        }
        if ("tools/call" == method) {
            return handleToolsCall(params)
        }
        if ("ping" == method) {
            return JSONObject()
        }
        throw RuntimeException("Unknown method: $method")
    }

    private fun handleInitialize(): JSONObject {
        val result = JSONObject()

        val serverInfo = JSONObject()
        serverInfo.put("name", "vortexdbg-mcp")
        serverInfo.put("version", "1.0.0")
        result.put("serverInfo", serverInfo)

        val capabilities = JSONObject()
        val tools = JSONObject()
        tools.put("listChanged", false)
        capabilities.put("tools", tools)
        val logging = JSONObject()
        capabilities.put("logging", logging)
        result.put("capabilities", capabilities)

        result.put("protocolVersion", "2024-11-05")
        result.put("instructions", buildInstructions())
        return result
    }

    private fun buildInstructions(): String {
        return "Vortex-DBG MCP — ARM emulator debugger for Android native libraries.\n\n" +
                "## State\n" +
                "- debug_idle=true: paused, tools ready. false: running, only execution tools + poll_events work.\n" +
                "- isRunning=true: cannot call call_function/call_symbol/allocate_memory(malloc).\n\n" +
                "## Modes\n" +
                "1. Breakpoint debug: paused at breakpoint, all tools available. Resume → next bp or exit.\n" +
                "2. Custom tools (DebugRunnable): repeatable execution, set bp/trace BEFORE calling custom tool, poll_events after.\n\n" +
                "## Execution Flow\n" +
                "Execution tools return immediately. Always poll_events after for breakpoint_hit/execution_completed.\n\n" +
                "## call_function/call_symbol Arg Format\n" +
                "Args array elements MUST be strings:\n" +
                "- Hex integer: \"0x1234\" or \"1234\" (BOTH parsed as hex. \"128\"=0x128=296 decimal)\n" +
                "- C string: \"s:hello world\" (auto-allocated)\n" +
                "- Byte array: \"b:48656c6c6f\" (hex-encoded, auto-allocated)\n" +
                "- Null: \"null\"\n\n" +
                "## Backend Capabilities\n" +
                "- Unicorn/Unicorn2: FULL (all tools)\n" +
                "- Hypervisor: PARTIAL (limited hw breakpoints, 1 code trace, no next_block/step_until_mnemonic)\n" +
                "- Dynarmic/KVM: MINIMAL (breakpoints only, no trace/step)\n\n" +
                "## Tips\n" +
                "- Prefer add_breakpoint_by_symbol/add_breakpoint_by_offset over find_symbol + add_breakpoint.\n" +
                "- Use read_string/read_std_string/read_typed/read_pointer for structured data.\n" +
                "- find_symbol only has .dynsym/exports. For stripped symbols: address = module_base + IDA_offset.\n" +
                "- allocate_memory: malloc when stopped (efficient), mmap when running (page-aligned). free_memory to release."
    }

    private fun handleToolsList(): JSONObject {
        val result = JSONObject()
        val toolsArray = mcpTools.getToolSchemas()
        // Standardize every advertised tool name with the "vortexdbg-" prefix.
        for (i in 0 until toolsArray.size) {
            val tool = toolsArray.getJSONObject(i)
            val name = tool.getString("name")
            if (name != null && !name.startsWith(TOOL_PREFIX)) {
                tool.put("name", TOOL_PREFIX + name)
            }
        }
        result.put("tools", toolsArray)
        return result
    }

    private fun handleToolsCall(params: JSONObject?): JSONObject {
        val raw = params!!.getString("name")
        // Accept both the prefixed name (canonical) and the bare name; dispatch uses the bare name.
        val name = if (raw != null && raw.startsWith(TOOL_PREFIX)) raw.substring(TOOL_PREFIX.length) else raw
        var arguments = params.getJSONObject("arguments")
        if (arguments == null) {
            arguments = JSONObject()
        }
        return mcpTools.callTool(name, arguments)
    }

    @Throws(IOException::class)
    private fun sendErrorResponse(exchange: HttpExchange, code: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.close()
    }

    fun setDebugIdle(idle: Boolean) {
        this.debugIdle = idle
    }

    fun isDebugIdle(): Boolean {
        return debugIdle
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(McpServer::class.java)

        /** Every MCP tool is advertised under this prefix so the tool names are namespaced. */
        const val TOOL_PREFIX = "vortexdbg-"
    }
}
