package org.bluepowerrobotics.converter.gateway.endpoints;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.bluepowerrobotics.converter.core.ChatChunk;
import org.bluepowerrobotics.converter.core.ChatMessage;
import org.bluepowerrobotics.converter.core.ChatModel;
import org.bluepowerrobotics.converter.core.ChatRequest;
import org.bluepowerrobotics.converter.core.ChatResponse;
import org.bluepowerrobotics.converter.core.ChatRole;
import org.bluepowerrobotics.converter.core.ChatStreamListener;
import org.bluepowerrobotics.converter.core.ToolCall;
import org.bluepowerrobotics.converter.gateway.HttpSupport;
import org.bluepowerrobotics.converter.util.Json;

/**
 * OpenAI Responses API 兼容端点：POST /v1/responses。
 * 支持非流式与 SSE 流式（stream: true）。
 */
public final class ResponsesEndpoint implements HttpHandler {

    private final ChatModel backend;
    private final String defaultModel;
    private final String forceModel;

    public ResponsesEndpoint(ChatModel backend, String defaultModel, String forceModel) {
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
                .messages(parseInput(body))
                .tools(RequestParsing.openAITools(body.get("tools")))
                .stream(body.path("stream").asBoolean(false))
                .temperature(RequestParsing.doubleField(body, "temperature"))
                .topP(RequestParsing.doubleField(body, "top_p"))
                .stop(RequestParsing.stringArray(body, "stop"))
                .maxTokens(RequestParsing.intField(body, "max_output_tokens"));
        RequestParsing.applyToolChoice(body, b);
        RequestParsing.applyResponseFormat(body, b);
        RequestParsing.applyResponsesTextFormat(body, b);
        RequestParsing.applyReasoning(body, b);
        return b.build();
    }

    private List<ChatMessage> parseInput(JsonNode body) {
        List<ChatMessage> out = new ArrayList<ChatMessage>();
        String instructions = body.path("instructions").asText(null);
        if (instructions != null) {
            out.add(ChatMessage.system(instructions));
        }
        JsonNode input = body.get("input");
        if (input == null || input.isNull()) {
            return out;
        }
        if (input.isTextual()) {
            out.add(ChatMessage.user(input.asText()));
            return out;
        }
        if (!input.isArray()) {
            return out;
        }
        for (JsonNode item : input) {
            String type = item.path("type").asText("");
            if ("function_call".equals(type)) {
                out.add(ChatMessage.builder().role(ChatRole.ASSISTANT)
                        .addToolCall(new ToolCall(
                                item.path("call_id").asText(null),
                                item.path("name").asText(null),
                                item.path("arguments").asText("{}")))
                        .build());
            } else if ("function_call_output".equals(type)) {
                out.add(ChatMessage.tool(
                        item.path("call_id").asText(null),
                        item.path("output").asText(null)));
            } else if ("message".equals(type) || item.has("role")) {
                String role = item.path("role").asText("user");
                String content = RequestParsing.openAIContent(item.get("content"));
                if ("system".equals(role)) {
                    out.add(ChatMessage.system(content));
                } else if ("assistant".equals(role)) {
                    out.add(ChatMessage.assistant(content));
                } else {
                    out.add(ChatMessage.user(content));
                }
            }
        }
        return out;
    }

    private void handleOnce(HttpExchange exchange, ChatRequest request) throws IOException {
        try {
            ChatResponse r = backend.complete(request);
            HttpSupport.sendJson(exchange, 200, toResponsesBody(r, request.getModel()));
        } catch (Exception e) {
            System.err.println("[responses] 后端请求失败: " + e);
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
        final String responseId = HttpSupport.newId("resp_");
        final String messageId = HttpSupport.newId("msg_");

        ObjectNode created = Json.MAPPER.createObjectNode();
        created.put("type", "response.created");
        ObjectNode respRef = Json.MAPPER.createObjectNode();
        respRef.put("id", responseId);
        respRef.put("object", "response");
        respRef.put("status", "in_progress");
        created.set("response", respRef);
        try {
            writer.data(created.toString());
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
                    delta.put("type", "response.output_text.delta");
                    delta.put("item_id", messageId);
                    delta.put("output_index", 0);
                    delta.put("content_index", 0);
                    delta.put("delta", chunk.getContent());
                    writer.data(delta.toString());
                } catch (IOException e) {
                    throw new HttpSupport.ClientGoneException(e);
                }
            }

            @Override
            public void onDone() {
                try {
                    ObjectNode done = Json.MAPPER.createObjectNode();
                    done.put("type", "response.completed");
                    ObjectNode resp = Json.MAPPER.createObjectNode();
                    resp.put("id", responseId);
                    resp.put("object", "response");
                    resp.put("status", "completed");
                    done.set("response", resp);
                    writer.data(done.toString());
                } catch (IOException e) {
                    throw new HttpSupport.ClientGoneException(e);
                }
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("[responses] 流式后端错误: " + error);
                try {
                    ObjectNode err = Json.MAPPER.createObjectNode();
                    err.put("type", "response.failed");
                    err.put("message", String.valueOf(error.getMessage()));
                    writer.data(err.toString());
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

    private ObjectNode toResponsesBody(ChatResponse r, String requestedModel) {
        ArrayNode output = Json.MAPPER.createArrayNode();
        if (r.getContent() != null) {
            ObjectNode msg = Json.MAPPER.createObjectNode();
            msg.put("id", HttpSupport.newId("msg_"));
            msg.put("type", "message");
            msg.put("status", "completed");
            msg.put("role", "assistant");
            ArrayNode content = Json.MAPPER.createArrayNode();
            ObjectNode textPart = Json.MAPPER.createObjectNode();
            textPart.put("type", "output_text");
            textPart.put("text", r.getContent());
            textPart.set("annotations", Json.MAPPER.createArrayNode());
            content.add(textPart);
            msg.set("content", content);
            output.add(msg);
        }
        for (ToolCall tc : r.getToolCalls()) {
            ObjectNode call = Json.MAPPER.createObjectNode();
            call.put("id", HttpSupport.newId("fc_"));
            call.put("type", "function_call");
            call.put("status", "completed");
            call.put("call_id", tc.getId());
            call.put("name", tc.getName());
            call.put("arguments", tc.getArgumentsJson());
            output.add(call);
        }

        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("id", r.getId() == null ? HttpSupport.newId("resp_") : r.getId());
        root.put("object", "response");
        root.put("created_at", HttpSupport.nowSeconds());
        root.put("status", "completed");
        root.put("model", r.getModel() == null
                ? (requestedModel == null ? defaultModel : requestedModel)
                : r.getModel());
        root.set("output", output);
        root.set("usage", ChatCompletionsEndpoint.usageNode(r.getUsage()));
        return root;
    }
}
