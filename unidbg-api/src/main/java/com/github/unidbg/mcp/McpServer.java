package com.github.unidbg.mcp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.unidbg.Emulator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    private final int port;
    private final McpTools mcpTools;
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private HttpServer httpServer;
    private ExecutorService executor;
    private PipedOutputStream commandPipe;
    private InputStream originalIn;
    private volatile boolean debugIdle;
    private final BlockingQueue<JSONObject> eventQueue = new LinkedBlockingQueue<>();

    public McpServer(Emulator<?> emulator, int port) {
        this.port = port;
        this.mcpTools = new McpTools(emulator, this);
    }

    public int getPort() {
        return port;
    }

    public void addCustomTool(String name, String description, String... paramNames) {
        mcpTools.addCustomTool(name, description, paramNames);
    }

    public void start() throws IOException {
        commandPipe = new PipedOutputStream();
        PipedInputStream pipedIn = new PipedInputStream(commandPipe, 4096);

        originalIn = System.in;
        System.setIn(new MergedInputStream(originalIn, pipedIn));

        ThreadFactory daemonFactory = r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("mcp-server");
            return t;
        };
        executor = Executors.newCachedThreadPool(daemonFactory);
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(executor);
        httpServer.createContext("/sse", this::handleSse);
        httpServer.createContext("/message", this::handleMessage);
        httpServer.start();
    }

    public void stop() {
        for (McpSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (commandPipe != null) {
            try {
                commandPipe.close();
            } catch (IOException ignored) {
            }
            commandPipe = null;
        }
        if (originalIn != null) {
            System.setIn(originalIn);
            originalIn = null;
        }
    }

    public void injectCommand(String command) {
        if (commandPipe != null) {
            try {
                commandPipe.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                commandPipe.flush();
            } catch (IOException e) {
                log.warn("Failed to inject command: {}", command, e);
            }
        }
    }

    private final Object operationLock = new Object();
    private volatile Callable<JSONObject> pendingOperation;
    private volatile JSONObject pendingResult;

    /**
     * Execute an operation on the debugger thread (required for backend operations like hardware breakpoints).
     * Blocks the calling thread until the debugger thread completes the operation.
     */
    public JSONObject runOnDebuggerThread(Callable<JSONObject> operation) {
        synchronized (operationLock) {
            pendingOperation = operation;
            pendingResult = null;
            injectCommand("_mcp");
            try {
                long deadline = System.currentTimeMillis() + 5000;
                while (pendingResult == null) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        pendingOperation = null;
                        return McpTools.errorResult("Operation timed out waiting for debugger thread");
                    }
                    operationLock.wait(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pendingOperation = null;
                return McpTools.errorResult("Operation interrupted");
            }
            return pendingResult;
        }
    }

    public void executePendingOperation() {
        synchronized (operationLock) {
            if (pendingOperation != null) {
                try {
                    pendingResult = pendingOperation.call();
                } catch (Exception e) {
                    pendingResult = McpTools.errorResult("Operation failed: " + e.getMessage());
                }
                pendingOperation = null;
                operationLock.notifyAll();
            }
        }
    }

    public void queueEvent(JSONObject event) {
        if (!eventQueue.offer(event)) {
            throw new IllegalStateException();
        }
    }

    public int getPendingEventCount() {
        return eventQueue.size();
    }

    public List<JSONObject> pollEvents(long timeoutMs) {
        List<JSONObject> events = new ArrayList<>();
        eventQueue.drainTo(events);
        if (!events.isEmpty() || timeoutMs <= 0) {
            return events;
        }
        try {
            JSONObject first = eventQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (first != null) {
                events.add(first);
                eventQueue.drainTo(events);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return events;
    }

    public void broadcastNotification(String event, JSONObject data) {
        JSONObject notification = new JSONObject();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/message");

        JSONObject params = new JSONObject();
        params.put("level", "info");
        params.put("logger", "unidbg");
        data.put("event", event);
        params.put("data", data.toJSONString());
        notification.put("params", params);

        sessions.entrySet().removeIf(entry -> entry.getValue().isClosed());
        for (McpSession session : sessions.values()) {
            session.sendNotification(notification);
        }
    }

    private void handleSse(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        log.debug("[MCP] /sse {} from {} headers={}", method, exchange.getRemoteAddress(),
                exchange.getRequestHeaders().entrySet());
        if ("POST".equals(method)) {
            handleStreamableHttp(exchange);
            return;
        }
        if ("OPTIONS".equals(method)) {
            log.debug("[MCP] /sse OPTIONS preflight");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Mcp-Session-Id");
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"GET".equals(method)) {
            log.debug("[MCP] /sse rejected method: {}", method);
            sendErrorResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        McpSession session = new McpSession();
        sessions.put(session.getSessionId(), session);
        log.debug("[MCP] SSE session created: {}", session.getSessionId());

        session.attachSseStream(exchange);
        session.sendEndpointEvent("http://localhost:" + port);

        while (!session.isClosed()) {
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                break;
            }
        }
        log.debug("[MCP] SSE session closed: {}", session.getSessionId());
        sessions.remove(session.getSessionId());
    }

    private void handleStreamableHttp(HttpExchange exchange) throws IOException {
        String body = IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8);
        log.debug("[MCP] /sse POST body: {}", body);
        JSONObject request;
        try {
            request = JSON.parseObject(body);
        } catch (Exception e) {
            log.warn("[MCP] Invalid JSON in /sse POST: {}", e.getMessage());
            sendErrorResponse(exchange, 400, "Invalid JSON: " + e.getMessage());
            return;
        }
        if (request == null) {
            sendErrorResponse(exchange, 400, "Empty JSON body");
            return;
        }

        String rpcMethod = request.getString("method");
        Object id = request.get("id");

        if (rpcMethod != null && rpcMethod.startsWith("notifications/")) {
            log.debug("[MCP] notification: {}", rpcMethod);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }

        JSONObject response = buildJsonRpcResponse(id, rpcMethod, request.getJSONObject("params"));

        byte[] responseBytes = response.toJSONString().getBytes(StandardCharsets.UTF_8);
        log.debug("[MCP] /sse POST response ({}): {} bytes", rpcMethod, responseBytes.length);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    private void handleMessage(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            sendErrorResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        String sessionId = null;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("sessionId=")) {
                    sessionId = param.substring("sessionId=".length());
                    break;
                }
            }
        }

        McpSession session = sessionId != null ? sessions.get(sessionId) : null;
        if (session == null) {
            sendErrorResponse(exchange, 400, "Invalid session");
            return;
        }

        String body = IOUtils.toString(exchange.getRequestBody(), StandardCharsets.UTF_8);
        JSONObject request;
        try {
            request = JSON.parseObject(body);
        } catch (Exception e) {
            log.warn("[MCP] Invalid JSON in /message: {}", e.getMessage());
            sendErrorResponse(exchange, 400, "Invalid JSON: " + e.getMessage());
            return;
        }
        if (request == null) {
            sendErrorResponse(exchange, 400, "Empty JSON body");
            return;
        }

        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(202, -1);
        exchange.close();

        String method = request.getString("method");
        Object id = request.get("id");

        if ("notifications/initialized".equals(method) || (method != null && method.startsWith("notifications/"))) {
            return;
        }

        JSONObject response = buildJsonRpcResponse(id, method, request.getJSONObject("params"));

        session.sendJsonRpcResponse(response);
    }

    private JSONObject buildJsonRpcResponse(Object id, String method, JSONObject params) {
        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        try {
            JSONObject result = dispatch(method, params);
            response.put("result", result);
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("code", -32603);
            error.put("message", e.getMessage());
            response.put("error", error);
        }
        return response;
    }

    private JSONObject dispatch(String method, JSONObject params) {
        if ("initialize".equals(method)) {
            return handleInitialize();
        }
        if ("tools/list".equals(method)) {
            return handleToolsList();
        }
        if ("tools/call".equals(method)) {
            return handleToolsCall(params);
        }
        if ("ping".equals(method)) {
            return new JSONObject();
        }
        throw new RuntimeException("Unknown method: " + method);
    }

    private JSONObject handleInitialize() {
        JSONObject result = new JSONObject();

        JSONObject serverInfo = new JSONObject();
        serverInfo.put("name", "unidbg-mcp");
        serverInfo.put("version", "1.0.0");
        result.put("serverInfo", serverInfo);

        JSONObject capabilities = new JSONObject();
        JSONObject tools = new JSONObject();
        tools.put("listChanged", false);
        capabilities.put("tools", tools);
        JSONObject logging = new JSONObject();
        capabilities.put("logging", logging);
        result.put("capabilities", capabilities);

        result.put("protocolVersion", "2024-11-05");
        result.put("instructions", buildInstructions());
        return result;
    }

    private String buildInstructions() {
        return "unidbg MCP — ARM emulator debugger for Android/iOS native libraries.\n\n" +
                "## State\n" +
                "- debug_idle=true: paused, tools ready. false: running, only execution tools + poll_events work.\n" +
                "- isRunning=true: cannot call call_function/call_symbol/dump_objc_class/dump_gpb_protobuf/allocate_memory(malloc).\n\n" +
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
                "- allocate_memory: malloc when stopped (efficient), mmap when running (page-aligned). free_memory to release.\n" +
                "- iOS: dump_objc_class/dump_gpb_protobuf call ObjC runtime internally, WILL modify registers/stack.";
    }

    private JSONObject handleToolsList() {
        JSONObject result = new JSONObject();
        JSONArray toolsArray = mcpTools.getToolSchemas();
        result.put("tools", toolsArray);
        return result;
    }

    private JSONObject handleToolsCall(JSONObject params) {
        String name = params.getString("name");
        JSONObject arguments = params.getJSONObject("arguments");
        if (arguments == null) {
            arguments = new JSONObject();
        }
        return mcpTools.callTool(name, arguments);
    }

    private void sendErrorResponse(HttpExchange exchange, int code, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public void setDebugIdle(boolean idle) {
        this.debugIdle = idle;
    }

    public boolean isDebugIdle() {
        return debugIdle;
    }
}
