package org.bluepowerrobotics.converter.gateway.endpoints;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import org.bluepowerrobotics.converter.core.ChatChunk;
import org.bluepowerrobotics.converter.core.ChatMessage;
import org.bluepowerrobotics.converter.core.ChatModel;
import org.bluepowerrobotics.converter.core.ChatRequest;
import org.bluepowerrobotics.converter.core.ChatResponse;
import org.bluepowerrobotics.converter.core.ChatStreamListener;
import org.bluepowerrobotics.converter.core.ToolCall;
import org.bluepowerrobotics.converter.gateway.HttpSupport;
import org.bluepowerrobotics.converter.util.Json;

/**
 * Anthropic Messages API 兼容端点：POST /v1/messages。
 * 支持非流式与 SSE 流式（stream: true）。
 */
public final class AnthropicMessagesEndpoint implements HttpHandler {

    private final ChatModel backend;
    private final String defaultModel;
    private final String forceModel;

    public AnthropicMessagesEndpoint(ChatModel backend, String defaultModel, String forceModel) {
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
        List<ChatMessage> messages = RequestParsing.anthropicMessages(body.get("messages"));
        String system = anthropicSystem(body.get("system"));
        if (system != null) {
            messages.add(0, ChatMessage.system(system));
        }
        ChatRequest.Builder b = ChatRequest.builder()
                .apiKey(clientApiKey)
                .model(forceModel != null
                        ? forceModel
                        : body.get("model") != null && !body.get("model").isNull()
                        ? body.get("model").asText()
                        : defaultModel)
                .messages(messages)
                .tools(RequestParsing.anthropicTools(body.get("tools")))
                .stream(body.path("stream").asBoolean(false))
                .temperature(RequestParsing.doubleField(body, "temperature"))
                .topP(RequestParsing.doubleField(body, "top_p"))
                .stop(RequestParsing.stringArray(body, "stop_sequences"))
                .maxTokens(RequestParsing.intField(body, "max_tokens"));
        RequestParsing.applyToolChoice(body, b);
        RequestParsing.applyReasoning(body, b);
        return b.build();
    }

    private static String anthropicSystem(JsonNode system) {
        if (system == null || system.isNull()) {
            return null;
        }
        if (system.isTextual()) {
            return system.asText();
        }
        if (system.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : system) {
                String t = part.path("text").asText("");
                if (sb.length() > 0 && !t.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(t);
            }
            return sb.toString();
        }
        return system.asText();
    }

    private void handleOnce(HttpExchange exchange, ChatRequest request) throws IOException {
        try {
            ChatResponse r = backend.complete(request);
            HttpSupport.sendJson(exchange, 200, toMessagesBody(r, request.getModel()));
        } catch (Exception e) {
            System.err.println("[messages] 后端请求失败: " + e);
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
        final String messageId = HttpSupport.newId("msg_");

        ObjectNode start = Json.MAPPER.createObjectNode();
        start.put("type", "message_start");
        ObjectNode startMsg = Json.MAPPER.createObjectNode();
        startMsg.put("id", messageId);
        startMsg.put("type", "message");
        startMsg.put("role", "assistant");
        startMsg.put("model", request.getModel() == null ? defaultModel : request.getModel());
        ObjectNode startUsage = Json.MAPPER.createObjectNode();
        startUsage.put("input_tokens", 0);
        startUsage.put("output_tokens", 0);
        startMsg.set("usage", startUsage);
        start.set("message", startMsg);

        ObjectNode blockStart = Json.MAPPER.createObjectNode();
        blockStart.put("type", "content_block_start");
        blockStart.put("index", 0);
        ObjectNode textBlock = Json.MAPPER.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", "");
        blockStart.set("content_block", textBlock);
        try {
            writer.event("message_start", start.toString());
            writer.event("content_block_start", blockStart.toString());
        } catch (IOException e) {
            exchange.getResponseBody().close();
            return;
        }

        backend.stream(request, new ChatStreamListener() {
            @Override
            public void onChunk(ChatChunk chunk) {
                if (chunk.getContent() == null) {
                    return;
                }
                try {
                    ObjectNode delta = Json.MAPPER.createObjectNode();
                    delta.put("type", "content_block_delta");
                    delta.put("index", 0);
                    ObjectNode textDelta = Json.MAPPER.createObjectNode();
                    textDelta.put("type", "text_delta");
                    textDelta.put("text", chunk.getContent());
                    delta.set("delta", textDelta);
                    writer.event("content_block_delta", delta.toString());
                } catch (IOException e) {
                    throw new HttpSupport.ClientGoneException(e);
                }
            }

            @Override
            public void onDone() {
                try {
                    ObjectNode blockStop = Json.MAPPER.createObjectNode();
                    blockStop.put("type", "content_block_stop");
                    blockStop.put("index", 0);
                    writer.event("content_block_stop", blockStop.toString());

                    ObjectNode msgDelta = Json.MAPPER.createObjectNode();
                    msgDelta.put("type", "message_delta");
                    ObjectNode delta = Json.MAPPER.createObjectNode();
                    delta.put("stop_reason", "end_turn");
                    delta.putNull("stop_sequence");
                    msgDelta.set("delta", delta);
                    ObjectNode deltaUsage = Json.MAPPER.createObjectNode();
                    deltaUsage.put("output_tokens", 0);
                    msgDelta.set("usage", deltaUsage);
                    writer.event("message_delta", msgDelta.toString());

                    ObjectNode stop = Json.MAPPER.createObjectNode();
                    stop.put("type", "message_stop");
                    writer.event("message_stop", stop.toString());
                } catch (IOException e) {
                    throw new HttpSupport.ClientGoneException(e);
                }
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("[messages] 流式后端错误: " + error);
                try {
                    ObjectNode err = Json.MAPPER.createObjectNode();
                    err.put("type", "error");
                    err.put("message", String.valueOf(error.getMessage()));
                    writer.event("error", err.toString());
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

    private ObjectNode toMessagesBody(ChatResponse r, String requestedModel) {
        ArrayNode content = Json.MAPPER.createArrayNode();
        if (r.getContent() != null) {
            ObjectNode textPart = Json.MAPPER.createObjectNode();
            textPart.put("type", "text");
            textPart.put("text", r.getContent());
            content.add(textPart);
        }
        for (ToolCall tc : r.getToolCalls()) {
            ObjectNode toolUse = Json.MAPPER.createObjectNode();
            toolUse.put("type", "tool_use");
            toolUse.put("id", tc.getId());
            toolUse.put("name", tc.getName());
            toolUse.set("input", Json.readTree(tc.getArgumentsJson()));
            content.add(toolUse);
        }

        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("id", r.getId() == null ? HttpSupport.newId("msg_") : r.getId());
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", r.getModel() == null
                ? (requestedModel == null ? defaultModel : requestedModel)
                : r.getModel());
        root.set("content", content);
        root.put("stop_reason", toStopReason(r));
        root.putNull("stop_sequence");
        ObjectNode usage = Json.MAPPER.createObjectNode();
        if (r.getUsage() == null) {
            usage.put("input_tokens", 0);
            usage.put("output_tokens", 0);
        } else {
            usage.put("input_tokens",
                    r.getUsage().getPromptTokens() == null ? 0 : r.getUsage().getPromptTokens());
            usage.put("output_tokens",
                    r.getUsage().getCompletionTokens() == null
                            ? 0
                            : r.getUsage().getCompletionTokens());
        }
        root.set("usage", usage);
        return root;
    }

    private static String toStopReason(ChatResponse r) {
        if (r.getFinishReason() == null) {
            return "end_turn";
        }
        switch (r.getFinishReason()) {
            case LENGTH:
                return "max_tokens";
            case TOOL_CALLS:
                return "tool_use";
            case CONTENT_FILTER:
                return "refusal";
            default:
                return "end_turn";
        }
    }
}
