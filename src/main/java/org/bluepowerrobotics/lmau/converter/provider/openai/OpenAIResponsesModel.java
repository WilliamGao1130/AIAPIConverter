package org.bluepowerrobotics.lmau.converter.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseFunctionCallArgumentsDoneEvent;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFormatTextConfig;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.ToolChoiceFunction;
import com.openai.models.responses.ToolChoiceOptions;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bluepowerrobotics.lmau.converter.core.*;
import org.bluepowerrobotics.lmau.converter.core.ChatModel;
import org.bluepowerrobotics.lmau.converter.core.ChatResponse;
import org.bluepowerrobotics.lmau.converter.provider.ProviderConfig;
import org.bluepowerrobotics.lmau.converter.util.Json;
import org.bluepowerrobotics.lmau.converter.util.ToolCallAccumulator;

/**
 * OpenAI Responses API 适配器，基于官方 com.openai:openai-java。
 */
public final class OpenAIResponsesModel implements ChatModel {

    private static final String NO_KEY_PLACEHOLDER = "sk-no-key-placeholder";

    private final String baseUrl;
    private final String defaultModel;
    private final String defaultApiKey;
    private final Map<String, OpenAIClient> clientsByKey = new ConcurrentHashMap<String, OpenAIClient>();

    public OpenAIResponsesModel(ProviderConfig config) {
        this.baseUrl = config.getBaseUrl();
        this.defaultModel = config.getModel();
        this.defaultApiKey = config.getApiKey();
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        try {
            ResponseCreateParams params = buildParams(request);
            Response response = clientFor(request).responses().create(params);
            return toResponse(response, effectiveModel(request));
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI responses call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(ChatRequest request, ChatStreamListener listener) {
        try {
            ResponseCreateParams params = buildParams(request);
            StreamResponse<ResponseStreamEvent> sr =
                    clientFor(request).responses().createStreaming(params);
            java.util.Map<String, ToolCallAccumulator> toolCallAccumulators =
                    new java.util.LinkedHashMap<String, ToolCallAccumulator>();
            try (java.util.stream.Stream<ResponseStreamEvent> stream = sr.stream()) {
                Iterator<ResponseStreamEvent> it = stream.iterator();
                while (it.hasNext()) {
                    ResponseStreamEvent ev = it.next();
                    if (ev.isOutputTextDelta()) {
                        listener.onChunk(new ChatChunk(ev.asOutputTextDelta().delta(), null));
                    } else if (ev.isReasoningSummaryTextDelta()) {
                        listener.onChunk(new ChatChunk(
                                null, ev.asReasoningSummaryTextDelta().delta(), null));
                    } else if (ev.isReasoningTextDelta()) {
                        listener.onChunk(new ChatChunk(
                                null, ev.asReasoningTextDelta().delta(), null));
                    } else if (ev.isFunctionCallArgumentsDelta()) {
                        ResponseFunctionCallArgumentsDeltaEvent delta =
                                ev.asFunctionCallArgumentsDelta();
                        ToolCallAccumulator acc = toolCallAccumulators.get(delta.itemId());
                        if (acc == null) {
                            acc = new ToolCallAccumulator();
                            acc.id = delta.itemId();
                            toolCallAccumulators.put(delta.itemId(), acc);
                        }
                        acc.args.append(delta.delta());
                    } else if (ev.isFunctionCallArgumentsDone()) {
                        ResponseFunctionCallArgumentsDoneEvent done =
                                ev.asFunctionCallArgumentsDone();
                        ToolCallAccumulator acc = toolCallAccumulators.get(done.itemId());
                        if (acc == null) {
                            acc = new ToolCallAccumulator();
                            toolCallAccumulators.put(done.itemId(), acc);
                        }
                        acc.id = done.itemId();
                        acc.name = done.name();
                        acc.args.setLength(0);
                        acc.args.append(done.arguments());
                    } else if (ev.isCompleted()) {
                        listener.onChunk(new ChatChunk(null, FinishReason.STOP));
                    } else if (ev.isFailed()) {
                        throw new IllegalStateException("OpenAI responses stream failed");
                    } else if (ev.isError()) {
                        throw new IllegalStateException("OpenAI responses stream error");
                    }
                }
            }
            if (!toolCallAccumulators.isEmpty()) {
                List<ToolCall> calls = new ArrayList<ToolCall>();
                for (ToolCallAccumulator acc : toolCallAccumulators.values()) {
                    calls.add(acc.toToolCall());
                }
                listener.onChunk(new ChatChunk(null, null, calls, FinishReason.TOOL_CALLS));
            }
            listener.onDone();
        } catch (Throwable t) {
            listener.onError(t);
        }
    }

    private ResponseCreateParams buildParams(ChatRequest request) {
        ResponseCreateParams.Builder b = ResponseCreateParams.builder()
                .model(effectiveModel(request));

        StringBuilder instructions = null;
        List<ResponseInputItem> items = new ArrayList<ResponseInputItem>();
        for (ChatMessage m : request.getMessages()) {
            if (m.getRole() == ChatRole.SYSTEM) {
                if (instructions == null) {
                    instructions = new StringBuilder();
                }
                if (m.getContent() == null) {
                    continue;
                }
                if (instructions.length() > 0) {
                    instructions.append('\n');
                }
                instructions.append(m.getContent());
                continue;
            }
            if (m.getRole() == ChatRole.USER) {
                items.add(ResponseInputItem.ofEasyInputMessage(toUserInput(m)));
            } else if (m.getRole() == ChatRole.ASSISTANT) {
                if (m.getToolCalls() == null || m.getToolCalls().isEmpty()) {
                    items.add(ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage.builder()
                                    .role(EasyInputMessage.Role.ASSISTANT)
                                    .content(EasyInputMessage.Content.ofTextInput(nonNull(m.getContent())))
                                    .build()));
                } else {
                    for (ToolCall tc : m.getToolCalls()) {
                        items.add(ResponseInputItem.ofFunctionCall(
                                ResponseFunctionToolCall.builder()
                                        .callId(tc.getId())
                                        .name(tc.getName())
                                        .arguments(tc.getArgumentsJson())
                                        .build()));
                    }
                }
            } else if (m.getRole() == ChatRole.TOOL) {
                items.add(ResponseInputItem.ofFunctionCallOutput(
                        ResponseInputItem.FunctionCallOutput.builder()
                                .callId(m.getToolCallId())
                                .output(nonNull(m.getContent()))
                                .build()));
            }
        }
        if (instructions != null) {
            b.instructions(instructions.toString());
        }
        b.input(ResponseCreateParams.Input.ofResponse(items));

        for (ToolDefinition t : request.getTools()) {
            b.addTool(toTool(t));
        }
        if (request.getTemperature() != null) {
            b.temperature(request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            b.maxOutputTokens(request.getMaxTokens().longValue());
        }
        if (request.getToolChoice() != null) {
            b.toolChoice(toToolChoice(request));
        }
        if (request.getResponseFormat() != null) {
            applyResponseFormat(b, request);
        }
        if (request.getReasoningEffort() != null
                && request.getReasoningEffort() != ChatRequest.ReasoningEffort.NONE) {
            b.reasoning(Reasoning.builder()
                    .effort(toOpenAIEffort(request.getReasoningEffort()))
                    .build());
        }
        return b.build();
    }

    private static ReasoningEffort toOpenAIEffort(ChatRequest.ReasoningEffort effort) {
        switch (effort) {
            case LOW:
                return ReasoningEffort.LOW;
            case MEDIUM:
                return ReasoningEffort.MEDIUM;
            case HIGH:
                return ReasoningEffort.HIGH;
            case XHIGH:
                return ReasoningEffort.XHIGH;
            default:
                return ReasoningEffort.MEDIUM;
        }
    }

    private static EasyInputMessage toUserInput(ChatMessage m) {
        if (m.hasContentParts()) {
            List<ResponseInputContent> parts = new ArrayList<ResponseInputContent>();
            for (ContentPart p : m.getContentParts()) {
                if (p.getType() == ContentPart.Type.TEXT) {
                    parts.add(ResponseInputContent.ofInputText(
                            ResponseInputText.builder().text(p.getText()).build()));
                } else {
                    parts.add(ResponseInputContent.ofInputImage(
                            ResponseInputImage.builder().imageUrl(p.getImageUrl()).build()));
                }
            }
            return EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.USER)
                    .content(EasyInputMessage.Content.ofResponseInputMessageContentList(parts))
                    .build();
        }
        return EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content(EasyInputMessage.Content.ofTextInput(nonNull(m.getContent())))
                .build();
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static ResponseCreateParams.ToolChoice toToolChoice(ChatRequest request) {
        switch (request.getToolChoice()) {
            case NONE:
                return ResponseCreateParams.ToolChoice.ofOptions(ToolChoiceOptions.NONE);
            case REQUIRED:
                return ResponseCreateParams.ToolChoice.ofOptions(ToolChoiceOptions.REQUIRED);
            case FUNCTION:
                return ResponseCreateParams.ToolChoice.ofFunction(
                        ToolChoiceFunction.builder()
                                .name(request.getToolChoiceFunction())
                                .build());
            case AUTO:
            default:
                return ResponseCreateParams.ToolChoice.ofOptions(ToolChoiceOptions.AUTO);
        }
    }

    private static void applyResponseFormat(
            ResponseCreateParams.Builder b, ChatRequest request) {
        if (request.getResponseFormat()
                == ResponseFormat.JSON_OBJECT) {
            ResponseFormatJsonObject jsonObject = ResponseFormatJsonObject.builder()
                    .type(JsonValue.from("json_object"))
                    .build();
            b.text(ResponseTextConfig.builder()
                    .format(ResponseFormatTextConfig.ofJsonObject(jsonObject))
                    .build());
            return;
        }
        if (request.getResponseFormat()
                == ResponseFormat.JSON_SCHEMA) {
            ResponseFormatTextJsonSchemaConfig.Builder sb =
                    ResponseFormatTextJsonSchemaConfig.builder()
                            .name(request.getResponseFormatName() == null
                                    ? "response_schema"
                                    : request.getResponseFormatName())
                            .type(JsonValue.from("json_schema"));
            ResponseFormatTextJsonSchemaConfig.Schema.Builder schemaBuilder =
                    ResponseFormatTextJsonSchemaConfig.Schema.builder();
            try {
                JsonNode node = Json.readTree(request.getResponseFormatSchema());
                if (node.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> e = fields.next();
                        schemaBuilder.putAdditionalProperty(
                                e.getKey(), JsonValue.fromJsonNode(e.getValue()));
                    }
                }
            } catch (Exception ignored) {
                // 无效 schema 时发送空对象
            }
            sb.schema(schemaBuilder.build());
            b.text(ResponseTextConfig.builder()
                    .format(ResponseFormatTextConfig.ofJsonSchema(sb.build()))
                    .build());
        }
    }

    private static FunctionTool toTool(ToolDefinition t) {
        FunctionTool.Builder b = FunctionTool.builder()
                .name(t.getName());
        if (t.getDescription() != null) {
            b.description(t.getDescription());
        }
        FunctionTool.Parameters.Builder pb = FunctionTool.Parameters.builder();
        try {
            JsonNode node = Json.readTree(t.getParametersJson());
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    pb.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue()));
                }
            }
        } catch (Exception ignored) {
            // 参数 JSON 无效时使用空对象
        }
        return b.parameters(pb.build()).build();
    }

    private ChatResponse toResponse(Response r, String model) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<ToolCall>();

        for (ResponseOutputItem item : r.output()) {
            if (item.isMessage()) {
                ResponseOutputMessage msg = item.asMessage();
                for (ResponseOutputMessage.Content part : msg.content()) {
                    if (part.isOutputText()) {
                        text.append(part.asOutputText().text());
                    }
                }
            } else if (item.isFunctionCall()) {
                ResponseFunctionToolCall fc = item.asFunctionCall();
                toolCalls.add(new ToolCall(fc.callId(), fc.name(), fc.arguments()));
            } else if (item.isReasoning()) {
                ResponseReasoningItem ri = item.asReasoning();
                if (ri.content().isPresent()) {
                    for (ResponseReasoningItem.Content part : ri.content().get()) {
                        if (part.text() != null) {
                            reasoning.append(part.text());
                        }
                    }
                }
                if (ri.summary() != null) {
                    for (ResponseReasoningItem.Summary summary : ri.summary()) {
                        reasoning.append(summary.text());
                    }
                }
            }
        }

        Usage usage = null;
        if (r.usage().isPresent()) {
            ResponseUsage u = r.usage().get();
            usage = new Usage(u.inputTokens(), u.outputTokens(), u.totalTokens());
        }
        return ChatResponse.builder()
                .content(text.toString())
                .reasoning(reasoning.length() == 0 ? null : reasoning.toString())
                .toolCalls(toolCalls)
                .finishReason(toolCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS)
                .usage(usage)
                .provider("openai-responses")
                .model(model)
                .id(r.id())
                .build();
    }

    private String effectiveModel(ChatRequest request) {
        return request.getModel() != null ? request.getModel() : defaultModel;
    }

    private OpenAIClient clientFor(ChatRequest request) {
        String key = request.getApiKey() != null ? request.getApiKey() : defaultApiKey;
        String cacheKey = key == null ? NO_KEY_PLACEHOLDER : key;
        OpenAIClient cached = clientsByKey.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        OpenAIClient created = buildClient(cacheKey);
        if (clientsByKey.size() >= 16) {
            for (OpenAIClient old : clientsByKey.values()) {
                try {
                    old.close();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
            clientsByKey.clear();
        }
        clientsByKey.put(cacheKey, created);
        return created;
    }

    private OpenAIClient buildClient(String apiKey) {
        OpenAIOkHttpClient.Builder ob = OpenAIOkHttpClient.builder()
                .apiKey(apiKey);
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            ob.baseUrl(baseUrl.trim());
        }
        return ob.build();
    }

    @Override
    public void close() {
        for (OpenAIClient cached : clientsByKey.values()) {
            try {
                cached.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
        clientsByKey.clear();
    }

    @Override
    public String toString() {
        return "OpenAIResponsesModel{model='" + defaultModel + "'}";
    }
}
