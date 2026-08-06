package org.bluepowerrobotics.lmau.converter.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import org.bluepowerrobotics.lmau.converter.util.Json;

/** 网关通用的 HTTP 读写工具。 */
public final class HttpSupport {

    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private HttpSupport() {
    }

    public static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BODY_BYTES) {
                    throw new IOException("Request body too large");
                }
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static JsonNode readJson(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body.trim().isEmpty()) {
            throw new IOException("Empty request body");
        }
        return Json.readTree(body);
    }

    public static void sendJson(HttpExchange exchange, int status, Object value)
            throws IOException {
        byte[] bytes = Json.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void sendError(HttpExchange exchange, int status, String type, String message)
            throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode error = Json.MAPPER.createObjectNode();
        error.put("message", message);
        error.put("type", type == null ? "invalid_request_error" : type);
        error.putNull("param");
        error.putNull("code");
        com.fasterxml.jackson.databind.node.ObjectNode root = Json.MAPPER.createObjectNode();
        root.set("error", error);
        sendJson(exchange, status, root);
    }

    public static String newId(String prefix) {
        return prefix + Long.toHexString(System.currentTimeMillis())
                + Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    /**
     * 从请求头提取客户端携带的 API key，优先 Authorization: Bearer，其次 x-api-key。
     * 网关不校验该 key，仅用于转发给后端（请求级覆盖）。
     */
    public static String extractApiKey(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String key = auth.substring("Bearer ".length()).trim();
            if (!key.isEmpty()) {
                return key;
            }
        }
        String xKey = exchange.getRequestHeaders().getFirst("x-api-key");
        return xKey == null || xKey.trim().isEmpty() ? null : xKey.trim();
    }

    /** 客户端断开时抛出，用于中止流式回调。 */
    public static final class ClientGoneException extends RuntimeException {
        public ClientGoneException(IOException cause) {
            super(cause);
        }
    }

    /** SSE 输出器。 */
    public static final class SseWriter {
        private final OutputStream out;

        public SseWriter(OutputStream out) {
            this.out = out;
        }

        public void data(String json) throws IOException {
            out.write("data: ".getBytes(StandardCharsets.UTF_8));
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.write("\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        public void event(String event, String json) throws IOException {
            out.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
            out.write("data: ".getBytes(StandardCharsets.UTF_8));
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.write("\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
