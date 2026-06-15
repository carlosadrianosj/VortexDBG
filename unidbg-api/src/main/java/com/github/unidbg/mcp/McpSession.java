package com.github.unidbg.mcp;

import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class McpSession {

    private static final Logger log = LoggerFactory.getLogger(McpSession.class);

    private final String sessionId;
    private volatile OutputStream sseOutput;
    private volatile boolean closed;

    public McpSession() {
        this.sessionId = UUID.randomUUID().toString();
    }

    public String getSessionId() {
        return sessionId;
    }

    public void attachSseStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0);
        this.sseOutput = exchange.getResponseBody();
        this.closed = false;
    }

    public void sendEndpointEvent(String baseUrl) {
        sendSseEvent("endpoint", baseUrl + "/message?sessionId=" + sessionId);
    }

    public void sendJsonRpcResponse(JSONObject response) {
        sendSseEvent("message", response.toJSONString());
    }

    public void sendNotification(JSONObject notification) {
        sendSseEvent("message", notification.toJSONString());
    }

    private synchronized void sendSseEvent(String event, String data) {
        if (closed || sseOutput == null) {
            return;
        }
        try {
            String payload = "event: " + event + "\ndata: " + data + "\n\n";
            sseOutput.write(payload.getBytes(StandardCharsets.UTF_8));
            sseOutput.flush();
        } catch (IOException e) {
            log.debug("SSE write failed, closing session {}", sessionId, e);
            close();
        }
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (sseOutput != null) {
            try {
                sseOutput.close();
            } catch (IOException ignored) {
            }
            sseOutput = null;
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
