package org.bluepowerrobotics.lmau.converter.gateway.endpoints;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

import org.bluepowerrobotics.lmau.converter.core.ChatChunk;
import org.bluepowerrobotics.lmau.converter.core.ChatModel;
import org.bluepowerrobotics.lmau.converter.core.ChatRequest;
import org.bluepowerrobotics.lmau.converter.core.ChatResponse;
import org.bluepowerrobotics.lmau.converter.core.ChatStreamListener;
import org.bluepowerrobotics.lmau.converter.core.ToolCall;
import org.bluepowerrobotics.lmau.converter.core.Usage;
import org.bluepowerrobotics.lmau.converter.gateway.HttpSupport;
import org.bluepowerrobotics.lmau.converter.util.Json;

/**
 * OpenAI Chat Completions 兼容端点：POST /v1/chat/completions。
 * 支持非流式与 SSE 流式（stream: true）。
 */
public final class ChatCompletionsEndpoint implements HttpHandler {

    private final ChatModel backend;
    private final String defaultModel;
    private final String forceModel;

    public ChatCompletionsEndpoint(ChatModel backend, String defaultModel, String forceModel) {
        this.backend = backend;
        this.defaultModel = defaultModel;
        this.forceModel = forceModel;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpSupport.sendError(exchange, 405, "invalid_request_error",
                    "Only POST is supported");
            return;
        }
        JsonNode body;
        try {
            body = HttpSupport.readJson(exchange);
        } catch (Exception e) {
            HttpSupport.sendError(exchange, 400, "invalid_request_error",
                    "Invalid JSON body: " + e.getMessage());
            return;
        }

        ChatRequest request = toRequest(body, HttpSupport.extractApiKey(exchange));
        if (request.isStream()) {
            handleStreaming(exchange, request);
        } else {
            handleOnce(exchange, request);
        }
    }

    private ChatRequest toRequest(JsonNode body, String clientApiKey) {
        ChatRequest.Builder b = ChatRequest.builder()
                .apiKey(clientApiKey)
                .model(forceModel != null
                        ? forceModel
                        : body.get("model") != null && !body.get("model").isNull()
                        ? body.get("model").asText()
                        : defaultModel)
                .messages(RequestParsing.sanitizeDanglingToolCalls(
                        RequestParsing.openAIMessages(body.get("messages"))))
                .tools(RequestParsing.openAITools(body.get("tools")))
                .stream(body.path("stream").asBoolean(false))
                .temperature(RequestParsing.doubleField(body, "temperature"))
                .topP(RequestParsing.doubleField(body, "top_p"))
                .stop(RequestParsing.stringArray(body, "stop"));
        Integer maxTokens = RequestParsing.intField(body, "max_tokens");
        if (maxTokens == null) {
            maxTokens = RequestParsing.intField(body, "max_completion_tokens");
        }
        b.maxTokens(maxTokens);
        RequestParsing.applyToolChoice(body, b);
        RequestParsing.applyResponseFormat(body, b);
        RequestParsing.applyReasoning(body, b);
        return b.build();
    }

    private void handleOnce(HttpExchange exchange, ChatRequest request) throws IOException {
        try {
            ChatResponse r = backend.complete(request);
            HttpSupport.sendJson(exchange, 200, toOpenAIResponse(r, request.getModel()));
        } catch (Exception e) {
            System.err.println("[chat] 后端请求失败: " + e);
            HttpSupport.sendError(exchange, 502, "upstream_error",
                    "Backend request failed: " + e.getMessage());
        }
    }

    private void handleStreaming(HttpExchange exchange, ChatRequest request) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        HttpSupport.SseWriter writer = new HttpSupport.SseWriter(exchange.getResponseBody());
        final String streamId = HttpSupport.newId("chatcmpl-");

        backend.stream(request, new ChatStreamListener() {
            @Override
            public void onChunk(ChatChunk chunk) {
                try {
                    if (chunk.getContent() != null) {
                        writer.data(chunkJson(streamId, request, chunk.getContent(), null));
                    }
                    if (chunk.getReasoning() != null) {
                        writer.data(chunkJson(streamId, request, chunk.getReasoning(),
                                null, true));
                    }
                    if (chunk.getFinishReason() != null) {
                        writer.data(chunkJson(streamId, request, null,
                                chunk.getFinishReason().wire()));
                    }
                } catch (IOException e) {
                    throw new HttpSupport.ClientGoneException(e);
                }
            }

            @Override
            public void onDone() {
                try {
                    writer.data("[DONE]");
                } catch (IOException e) {
                    throw new HttpSupport.ClientGoneException(e);
                }
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("[chat] 流式后端错误: " + error);
                try {
                    ObjectNode err = Json.MAPPER.createObjectNode();
                    err.put("message", String.valueOf(error.getMessage()));
                    err.put("type", "upstream_error");
                    err.putNull("param");
                    err.putNull("code");
                    ObjectNode ev = Json.MAPPER.createObjectNode();
                    ev.set("error", err);
                    writer.data(ev.toString());
                    writer.data("[DONE]");
                } catch (IOException e) {
                    throw new HttpSupport.ClientGoneException(e);
                }
            }
        });
        try {
            exchange.getResponseBody().close();
        } catch (HttpSupport.ClientGoneException ignored) {
            // 客户端断开
        }
    }

    private String chunkJson(String id, ChatRequest request, String content, String finishReason) {
        return chunkJson(id, request, content, finishReason, false);
    }

    private String chunkJson(String id, ChatRequest request, String content,
                             String finishReason, boolean reasoning) {
        ObjectNode choice = Json.MAPPER.createObjectNode();
        choice.put("index", 0);
        ObjectNode delta = Json.MAPPER.createObjectNode();
        if (content != null) {
            if (reasoning) {
                delta.put("reasoning_content", content);
            } else {
                delta.put("content", content);
            }
        }
        choice.set("delta", delta);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }
        choice.putNull("logprobs");

        ArrayNode choices = Json.MAPPER.createArrayNode();
        choices.add(choice);
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("id", id);
        root.put("object", "chat.completion.chunk");
        root.put("created", HttpSupport.nowSeconds());
        root.put("model", request.getModel() == null ? defaultModel : request.getModel());
        root.set("choices", choices);
        return root.toString();
    }

    private ObjectNode toOpenAIResponse(ChatResponse r, String requestedModel) {
        ObjectNode message = Json.MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        if (r.getContent() != null) {
            message.put("content", r.getContent());
        }
        if (r.getReasoning() != null) {
            message.put("reasoning_content", r.getReasoning());
        }
        if (!r.getToolCalls().isEmpty()) {
            ArrayNode calls = Json.MAPPER.createArrayNode();
            for (ToolCall tc : r.getToolCalls()) {
                ObjectNode call = Json.MAPPER.createObjectNode();
                call.put("id", tc.getId());
                call.put("type", "function");
                ObjectNode fn = Json.MAPPER.createObjectNode();
                fn.put("name", tc.getName());
                fn.put("arguments", tc.getArgumentsJson());
                call.set("function", fn);
                calls.add(call);
            }
            message.set("tool_calls", calls);
        }

        ObjectNode choice = Json.MAPPER.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason",
                r.getFinishReason() == null ? "stop" : r.getFinishReason().wire());
        choice.putNull("logprobs");
        ArrayNode choices = Json.MAPPER.createArrayNode();
        choices.add(choice);

        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("id", r.getId() == null ? HttpSupport.newId("chatcmpl-") : r.getId());
        root.put("object", "chat.completion");
        root.put("created", HttpSupport.nowSeconds());
        root.put("model", r.getModel() == null
                ? (requestedModel == null ? defaultModel : requestedModel)
                : r.getModel());
        root.set("choices", choices);
        root.set("usage", usageNode(r.getUsage()));
        return root;
    }

    static ObjectNode usageNode(Usage usage) {
        ObjectNode u = Json.MAPPER.createObjectNode();
        if (usage == null) {
            u.put("prompt_tokens", 0);
            u.put("completion_tokens", 0);
            u.put("total_tokens", 0);
        } else {
            u.put("prompt_tokens", usage.getPromptTokens() == null ? 0 : usage.getPromptTokens());
            u.put("completion_tokens",
                    usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
            u.put("total_tokens", usage.getTotalTokens() == null ? 0 : usage.getTotalTokens());
        }
        return u;
    }
}
