package org.bluepowerrobotics.lmau.converter.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.bluepowerrobotics.lmau.converter.core.ChatChunk;
import org.bluepowerrobotics.lmau.converter.core.ChatModel;
import org.bluepowerrobotics.lmau.converter.core.ChatRequest;
import org.bluepowerrobotics.lmau.converter.core.ChatResponse;
import org.bluepowerrobotics.lmau.converter.core.ChatStreamListener;
import org.bluepowerrobotics.lmau.converter.core.FinishReason;
import org.bluepowerrobotics.lmau.converter.core.Usage;
import org.bluepowerrobotics.lmau.converter.provider.ProviderConfig;
import org.bluepowerrobotics.lmau.converter.util.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GatewayIntegrationTest {

    private static GatewayServer server;
    private static String base;

    @BeforeAll
    static void startServer() throws Exception {
        GatewayConfig config = GatewayConfig.builder()
                .host("127.0.0.1")
                .port(18923)
                .backend(ProviderConfig.builder()
                        .type(ProviderConfig.ProviderType.DASHSCOPE)
                        .apiKey("test-key")
                        .model("qwen-plus")
                        .build())
                .build();
        server = GatewayServer.start(config, new StubModel());
        base = server.getAddress();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @Test
    void healthCheck() throws Exception {
        JsonNode json = post("/health", "{}", "GET");
        assertEquals("ok", json.path("status").asText());
    }

    @Test
    void modelsList() throws Exception {
        JsonNode json = post("/v1/models", "{}", "GET");
        assertEquals("list", json.path("object").asText());
        assertEquals("qwen-plus", json.path("data").get(0).path("id").asText());
    }

    @Test
    void chatCompletionsNonStreaming() throws Exception {
        String body = "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\","
                + "\"content\":\"hello\"}],\"temperature\":0.7}";
        JsonNode json = post("/v1/chat/completions", body, "POST");
        assertEquals("chat.completion", json.path("object").asText());
        assertEquals("assistant", json.path("choices").get(0).path("message").path("role").asText());
        assertEquals("echo: hello",
                json.path("choices").get(0).path("message").path("content").asText());
        assertEquals("stop", json.path("choices").get(0).path("finish_reason").asText());
        assertEquals(3, json.path("usage").path("total_tokens").asLong());
    }

    @Test
    void chatCompletionsStreaming() throws Exception {
        String body = "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\","
                + "\"content\":\"hi\"}],\"stream\":true}";
        String sse = postRaw("/v1/chat/completions", body, "POST");
        assertTrue(sse.contains("data: "));
        assertTrue(sse.contains("\"object\":\"chat.completion.chunk\""));
        assertTrue(sse.contains("data: [DONE]"));
    }

    @Test
    void responsesNonStreaming() throws Exception {
        String body = "{\"model\":\"gpt-4o\",\"input\":\"hello there\"}";
        JsonNode json = post("/v1/responses", body, "POST");
        assertEquals("response", json.path("object").asText());
        assertEquals("completed", json.path("status").asText());
        assertEquals("echo: hello there",
                json.path("output").get(0).path("content").get(0).path("text").asText());
    }

    @Test
    void anthropicNonStreaming() throws Exception {
        String body = "{\"model\":\"claude-sonnet-4\",\"max_tokens\":128,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        JsonNode json = post("/v1/messages", body, "POST");
        assertEquals("message", json.path("type").asText());
        assertEquals("assistant", json.path("role").asText());
        assertEquals("echo: hi", json.path("content").get(0).path("text").asText());
        assertEquals("end_turn", json.path("stop_reason").asText());
    }

    @Test
    void invalidJsonReturns400() throws Exception {
        int code = postCode("/v1/chat/completions", "{not json", "POST");
        assertEquals(400, code);
    }

    @Test
    void multimodalContentAccepted() throws Exception {
        String body = "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"describe this\"},"
                + "{\"type\":\"image_url\",\"image_url\":\"https://example.com/a.png\"}"
                + "]}]}";
        JsonNode json = post("/v1/chat/completions", body, "POST");
        assertEquals("echo: describe this",
                json.path("choices").get(0).path("message").path("content").asText());
    }

    @Test
    void toolChoiceAndResponseFormatAccepted() throws Exception {
        String body = "{\"model\":\"qwen-plus\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}],"
                + "\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}},"
                + "\"response_format\":{\"type\":\"json_object\"}}";
        JsonNode json = post("/v1/chat/completions", body, "POST");
        assertEquals("chat.completion", json.path("object").asText());
    }

    @Test
    void disabledEndpointsReturn404() throws Exception {
        GatewayConfig config = GatewayConfig.builder()
                .host("127.0.0.1")
                .port(18924)
                .backend(ProviderConfig.builder()
                        .type(ProviderConfig.ProviderType.DASHSCOPE)
                        .apiKey("test-key")
                        .model("qwen-plus")
                        .build())
                .enabledEndpoints(java.util.Arrays.asList(GatewayConfig.EP_CHAT))
                .build();
        GatewayServer local = GatewayServer.start(config, new StubModel());
        try {
            assertEquals(200, postCodeAt(local.getAddress(),
                    "/v1/chat/completions", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "POST"));
            assertEquals(404, postCodeAt(local.getAddress(),
                    "/v1/responses", "{\"input\":\"hi\"}", "POST"));
            assertEquals(404, postCodeAt(local.getAddress(),
                    "/v1/messages", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "POST"));
        } finally {
            local.stop();
        }
    }

    @Test
    void forceModelOverridesClientModel() throws Exception {
        GatewayConfig config = GatewayConfig.builder()
                .host("127.0.0.1")
                .port(18925)
                .backend(ProviderConfig.builder()
                        .type(ProviderConfig.ProviderType.DASHSCOPE)
                        .apiKey("test-key")
                        .model("qwen-plus")
                        .build())
                .forceModel("deepseek-chat")
                .build();
        GatewayServer local = GatewayServer.start(config, new ModelEchoStub());
        try {
            JsonNode json = postJsonAt(local.getAddress(), "/v1/chat/completions",
                    "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "POST");
            assertEquals("model=deepseek-chat",
                    json.path("choices").get(0).path("message").path("content").asText());
        } finally {
            local.stop();
        }
    }

    @Test
    void clientApiKeyIsForwarded() throws Exception {
        GatewayConfig config = GatewayConfig.builder()
                .host("127.0.0.1")
                .port(18926)
                .backend(ProviderConfig.builder()
                        .type(ProviderConfig.ProviderType.DASHSCOPE)
                        .apiKey("gateway-key")
                        .model("qwen-plus")
                        .build())
                .build();
        GatewayServer local = GatewayServer.start(config, new KeyEchoStub());
        try {
            String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            JsonNode bearer = postJsonAtWithHeader(local.getAddress(),
                    "/v1/chat/completions", body, "Authorization", "Bearer sk-client-123", "POST");
            assertEquals("key=sk-client-123",
                    bearer.path("choices").get(0).path("message").path("content").asText());

            JsonNode xKey = postJsonAtWithHeader(local.getAddress(),
                    "/v1/chat/completions", body, "x-api-key", "sk-x-key-456", "POST");
            assertEquals("key=sk-x-key-456",
                    xKey.path("choices").get(0).path("message").path("content").asText());
        } finally {
            local.stop();
        }
    }

    @Test
    void multiPortClusterStartsAllGateways() throws Exception {
        List<GatewayConfig> configs = Arrays.asList(
                GatewayConfig.builder()
                        .host("127.0.0.1")
                        .port(18927)
                        .backend(ProviderConfig.builder()
                                .type(ProviderConfig.ProviderType.DASHSCOPE)
                                .apiKey("k1")
                                .model("qwen-plus")
                                .build())
                        .build(),
                GatewayConfig.builder()
                        .host("127.0.0.1")
                        .port(18928)
                        .backend(ProviderConfig.builder()
                                .type(ProviderConfig.ProviderType.ANTHROPIC)
                                .apiKey("k2")
                                .model("claude-sonnet-4")
                                .build())
                        .build());
        GatewayCluster cluster = GatewayCluster.start(configs);
        try {
            assertEquals(2, cluster.getServers().size());
            JsonNode h1 = post("http://127.0.0.1:18927", "/health", "{}", "GET");
            assertEquals("ok", h1.path("status").asText());
            JsonNode h2 = post("http://127.0.0.1:18928", "/health", "{}", "GET");
            assertEquals("ok", h2.path("status").asText());
        } finally {
            cluster.stop();
        }
    }

    private static JsonNode post(String path, String body, String method) throws Exception {
        return post(base, path, body, method);
    }

    private static JsonNode post(String urlBase, String path, String body, String method)
            throws Exception {
        HttpURLConnection conn = open(urlBase, path, method);
        if (!"GET".equals(method)) {
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        String text = readAll(conn);
        conn.disconnect();
        assertEquals(200, code, "HTTP " + code + ": " + text);
        return Json.readTree(text);
    }

    private static String postRaw(String path, String body, String method) throws Exception {
        HttpURLConnection conn = open(path, method);
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String text = readAll(conn);
        conn.disconnect();
        assertEquals(200, code, "HTTP " + code + ": " + text);
        return text;
    }

    private static int postCode(String path, String body, String method) throws Exception {
        return postCodeAt(base, path, body, method);
    }

    private static int postCodeAt(String urlBase, String path, String body, String method)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlBase + path).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private static JsonNode postJsonAt(String urlBase, String path, String body, String method)
            throws Exception {
        return postJsonAtWithHeader(urlBase, path, body, null, null, method);
    }

    private static JsonNode postJsonAtWithHeader(
            String urlBase, String path, String body,
            String headerName, String headerValue, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlBase + path).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        if (headerName != null) {
            conn.setRequestProperty(headerName, headerValue);
        }
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String text = readAll(conn);
        conn.disconnect();
        assertEquals(200, code, "HTTP " + code + ": " + text);
        return Json.readTree(text);
    }

    private static HttpURLConnection open(String path, String method) throws Exception {
        return open(base, path, method);
    }

    private static HttpURLConnection open(String urlBase, String path, String method)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlBase + path).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        return conn;
    }

    private static String readAll(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    private static final class StubModel implements ChatModel {

        @Override
        public ChatResponse complete(ChatRequest request) {
            String input = request.getMessages().isEmpty()
                    ? ""
                    : request.getMessages().get(0).getContent();
            return ChatResponse.builder()
                    .content("echo: " + input)
                    .finishReason(FinishReason.STOP)
                    .provider("stub")
                    .model("stub-model")
                    .usage(new Usage(1L, 2L, 3L))
                    .build();
        }

        @Override
        public void stream(ChatRequest request, ChatStreamListener listener) {
            listener.onChunk(new ChatChunk("Hello", null));
            listener.onChunk(new ChatChunk(" world", FinishReason.STOP));
            listener.onDone();
        }

        @Override
        public void close() {
        }
    }

    private static final class ModelEchoStub implements ChatModel {

        @Override
        public ChatResponse complete(ChatRequest request) {
            return ChatResponse.builder()
                    .content("model=" + request.getModel())
                    .finishReason(FinishReason.STOP)
                    .provider("stub")
                    .model("stub-model")
                    .build();
        }

        @Override
        public void stream(ChatRequest request, ChatStreamListener listener) {
            listener.onDone();
        }

        @Override
        public void close() {
        }
    }

    private static final class KeyEchoStub implements ChatModel {

        @Override
        public ChatResponse complete(ChatRequest request) {
            return ChatResponse.builder()
                    .content("key=" + request.getApiKey())
                    .finishReason(FinishReason.STOP)
                    .provider("stub")
                    .model("stub-model")
                    .build();
        }

        @Override
        public void stream(ChatRequest request, ChatStreamListener listener) {
            listener.onDone();
        }

        @Override
        public void close() {
        }
    }
}
