package org.bluepowerrobotics.converter.provider.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import org.bluepowerrobotics.converter.core.ChatMessage;
import org.bluepowerrobotics.converter.core.ChatRequest;
import org.bluepowerrobotics.converter.core.ChatResponse;
import org.bluepowerrobotics.converter.core.ChatRole;
import org.bluepowerrobotics.converter.provider.ProviderConfig;
import org.junit.jupiter.api.Test;

/**
 * 回归测试：OpenAI Chat Completions 适配器要求 assistant 等消息的
 * content 非空，统一适配层允许 null，必须归一化为空串而不是抛 NPE。
 */
class OpenAIChatCompletionsModelTest {

    private static final String COMPLETION_JSON =
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,"
            + "\"model\":\"m\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
            + "\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    @Test
    void nullAssistantContentIsNormalizedToEmptyString() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions",
                exchange -> respond(exchange, capturedBody));
        server.start();
        try {
            OpenAIChatCompletionsModel model = new OpenAIChatCompletionsModel(
                    ProviderConfig.builder()
                            .type(ProviderConfig.ProviderType.OPENAI_CHAT_COMPLETIONS)
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .apiKey("sk-test")
                            .model("m")
                            .build());

            ChatResponse response = model.complete(ChatRequest.builder()
                    .model("m")
                    .addMessage(ChatMessage.user("hi"))
                    .addMessage(ChatMessage.builder()
                            .role(ChatRole.ASSISTANT)
                            .content(null)
                            .build())
                    .build());

            assertEquals("ok", response.getContent());
            String body = capturedBody.get();
            assertTrue(body != null && body.contains("\"role\":\"assistant\""),
                    "请求应包含 assistant 消息");
            assertFalse(body.contains("\"content\":null"),
                    "content 不应以 null 序列化（修复前这里会 NPE）");
        } finally {
            server.stop(0);
        }
    }

    /** 思考强度必须真正映射：DeepSeek 兼容端点 NONE 显式关思考、XHIGH 用 max。 */
    @Test
    void reasoningEffortIsForwardedForDeepSeek() {
        assertReasoning(ChatRequest.ReasoningEffort.NONE, true,
                null, "disabled", true);
        assertReasoning(ChatRequest.ReasoningEffort.LOW, true,
                "low", null, false);
        assertReasoning(ChatRequest.ReasoningEffort.MEDIUM, true,
                "medium", null, false);
        assertReasoning(ChatRequest.ReasoningEffort.HIGH, true,
                "high", null, false);
        assertReasoning(ChatRequest.ReasoningEffort.XHIGH, true,
                "max", null, false);
    }

    /** 官方 OpenAI 端点不接受 thinking/xhigh：NONE 省略、XHIGH 收敛为 high。 */
    @Test
    void reasoningEffortIsForwardedForPlainOpenAI() {
        assertReasoning(null, false, null, null, false);
        assertReasoning(ChatRequest.ReasoningEffort.NONE, false,
                null, null, false);
        assertReasoning(ChatRequest.ReasoningEffort.XHIGH, false,
                "high", null, false);
    }

    private static void assertReasoning(
            ChatRequest.ReasoningEffort effort,
            boolean deepSeek,
            String expectedEffort,
            String expectedThinkingType,
            boolean expectThinkingField) {
        ChatCompletionCreateParams.Body.Builder b = ChatCompletionCreateParams.Body.builder()
                .model("m")
                .addMessage(ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder().content("hi").build()));
        OpenAIChatCompletionsModel.applyReasoning(b, effort, deepSeek);
        ChatCompletionCreateParams.Body body = b.build();

        java.util.Optional<ReasoningEffort> actual = body.reasoningEffort();
        if (expectedEffort == null) {
            assertFalse(actual.isPresent(), "effort=" + effort + " deepSeek=" + deepSeek
                    + " 不应设置 reasoning_effort");
        } else {
            assertTrue(actual.isPresent(), "effort=" + effort + " 应设置 reasoning_effort");
            assertEquals(expectedEffort, actual.get().asString());
        }

        Map<String, com.openai.core.JsonValue> extra = body._additionalProperties();
        assertEquals(expectThinkingField, extra.containsKey("thinking"),
                "effort=" + effort + " deepSeek=" + deepSeek + " thinking 字段存在性不符");
        if (expectThinkingField) {
            Map<String, Object> obj = extra.get("thinking")
                    .convert(new TypeReference<Map<String, Object>>() {});
            assertEquals(expectedThinkingType, obj.get("type"));
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,
                                AtomicReference<String> capturedBody) throws IOException {
        capturedBody.set(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
        byte[] resp = COMPLETION_JSON.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
