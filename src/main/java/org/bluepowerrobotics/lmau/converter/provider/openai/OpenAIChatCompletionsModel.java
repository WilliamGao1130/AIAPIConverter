package org.bluepowerrobotics.lmau.converter.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 * OpenAI Chat Completions API 适配器，基于官方 com.openai:openai-java。
 * 既可用于 OpenAI 官方端点，也可配合任何 OpenAI 兼容端点（baseUrl 自定义）。
 */
public final class OpenAIChatCompletionsModel implements ChatModel {

    private static final String NO_KEY_PLACEHOLDER = "sk-no-key-placeholder";

    private final String baseUrl;
    private final String defaultModel;
    private final String defaultApiKey;
    private final Map<String, OpenAIClient> clientsByKey = new ConcurrentHashMap<String, OpenAIClient>();

    public OpenAIChatCompletionsModel(ProviderConfig config) {
        this.baseUrl = config.getBaseUrl();
        this.defaultModel = config.getModel();
        this.defaultApiKey = config.getApiKey();
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        try {
            ChatCompletionCreateParams params = buildParams(request);
            ChatCompletion completion =
                    clientFor(request).chat().completions().create(params);
            return toResponse(completion, effectiveModel(request));
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI chat completion failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(ChatRequest request, ChatStreamListener listener) {
        try {
            ChatCompletionCreateParams params = buildParams(request);
            StreamResponse<ChatCompletionChunk> sr =
                    clientFor(request).chat().completions().createStreaming(params);
            Map<Long, ToolCallAccumulator> toolCallAccumulators =
                    new LinkedHashMap<Long, ToolCallAccumulator>();
            try (java.util.stream.Stream<ChatCompletionChunk> stream = sr.stream()) {
                Iterator<ChatCompletionChunk> it = stream.iterator();
                while (it.hasNext()) {
                    ChatCompletionChunk chunk = it.next();
                    for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                        ChatCompletionChunk.Choice.Delta delta = choice.delta();
                        if (delta.content().isPresent()) {
                            listener.onChunk(new ChatChunk(delta.content().get(), null));
                        }
                        String reasoning = reasoningContent(delta._additionalProperties());
                        if (reasoning != null) {
                            listener.onChunk(new ChatChunk(null, reasoning, null));
                        }
                        if (delta.toolCalls().isPresent()) {
                            for (ChatCompletionChunk.Choice.Delta.ToolCall toolCall
                                    : delta.toolCalls().get()) {
                                ToolCallAccumulator acc = toolCallAccumulators.get(toolCall.index());
                                if (acc == null) {
                                    acc = new ToolCallAccumulator();
                                    toolCallAccumulators.put(toolCall.index(), acc);
                                }
                                if (toolCall.id().isPresent()) {
                                    acc.id = toolCall.id().get();
                                }
                                if (toolCall.function().isPresent()) {
                                    ChatCompletionChunk.Choice.Delta.ToolCall.Function function =
                                            toolCall.function().get();
                                    if (function.name().isPresent()) {
                                        acc.name = function.name().get();
                                    }
                                    if (function.arguments().isPresent()) {
                                        acc.args.append(function.arguments().get());
                                    }
                                }
                            }
                        }
                        if (choice.finishReason().isPresent()) {
                            listener.onChunk(new ChatChunk(
                                    null, toFinishReason(choice.finishReason().get())));
                        }
                    }
                }
            }
            emitToolCalls(listener, toolCallAccumulators);
            listener.onDone();
        } catch (Throwable t) {
            listener.onError(t);
        }
    }

    /** 本轮流式结束：把累积的工具调用拼成一个携带 toolCalls 的收尾块。 */
    private static void emitToolCalls(ChatStreamListener listener,
                                      Map<Long, ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) {
            return;
        }
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (ToolCallAccumulator acc : accumulators.values()) {
            calls.add(acc.toToolCall());
        }
        listener.onChunk(new ChatChunk(null, null, calls, FinishReason.TOOL_CALLS));
    }

    private ChatCompletionCreateParams buildParams(ChatRequest request) {
        ChatCompletionCreateParams.Body.Builder b = ChatCompletionCreateParams.Body.builder()
                .model(effectiveModel(request));
        for (ChatMessage m : request.getMessages()) {
            b.addMessage(toMessageParam(m));
        }
        for (ToolDefinition t : request.getTools()) {
            b.addTool(toTool(t));
        }
        if (request.getTemperature() != null) {
            b.temperature(request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            b.maxCompletionTokens(request.getMaxTokens().longValue());
        }
        if (request.getTopP() != null) {
            b.topP(request.getTopP());
        }
        if (!request.getStop().isEmpty()) {
            if (request.getStop().size() == 1) {
                b.stop(request.getStop().get(0));
            } else {
                b.stop(ChatCompletionCreateParams.Stop.ofStrings(request.getStop()));
            }
        }
        if (request.getToolChoice() != null) {
            b.toolChoice(toToolChoice(request));
        }
        if (request.getResponseFormat() != null) {
            applyResponseFormat(b, request);
        }
        applyReasoning(b, request.getReasoningEffort());
        return ChatCompletionCreateParams.builder().body(b.build()).build();
    }

    /**
     * 深度思考控制：chat-completions 适配器此前完全没有透传 reasoning effort，
     * 导致 DeepSeek V4 这类默认开启思考的兼容端点对“关闭/强度”无响应。
     * - NONE：对 DeepSeek 显式发送 thinking.type=disabled（其 OpenAI 兼容接口
     *   默认开思考，必须显式关闭）；其他端点省略该参数（保持官方 OpenAI 行为）。
     * - LOW/MEDIUM/HIGH：映射为 reasoning_effort。
     * - XHIGH：DeepSeek 映射为 max（其文档接受 low/high/max，xhigh 会退化为
     *   high）；官方 OpenAI 聊天端点没有 xhigh，收敛为 high。
     */
    private void applyReasoning(
            ChatCompletionCreateParams.Body.Builder b, ChatRequest.ReasoningEffort effort) {
        applyReasoning(b, effort, isDeepSeekCompatible());
    }

    static void applyReasoning(
            ChatCompletionCreateParams.Body.Builder b,
            ChatRequest.ReasoningEffort effort,
            boolean deepSeekCompatible) {
        if (effort == null) {
            return;
        }
        if (effort == ChatRequest.ReasoningEffort.NONE) {
            if (deepSeekCompatible) {
                b.putAdditionalProperty("thinking",
                        JsonValue.fromJsonNode(Json.readTree("{\"type\":\"disabled\"}")));
            }
            return;
        }
        b.reasoningEffort(toOpenAIEffort(effort, deepSeekCompatible));
    }

    private boolean isDeepSeekCompatible() {
        return baseUrl != null && baseUrl.toLowerCase().contains("deepseek.com");
    }

    static ReasoningEffort toOpenAIEffort(
            ChatRequest.ReasoningEffort effort, boolean deepSeekCompatible) {
        switch (effort) {
            case LOW:
                return ReasoningEffort.LOW;
            case MEDIUM:
                return ReasoningEffort.MEDIUM;
            case HIGH:
                return ReasoningEffort.HIGH;
            case XHIGH:
                return deepSeekCompatible ? ReasoningEffort.MAX : ReasoningEffort.HIGH;
            default:
                return ReasoningEffort.MEDIUM;
        }
    }

    private ChatCompletionMessageParam toMessageParam(ChatMessage m) {
        if (m.getRole() == ChatRole.SYSTEM) {
            return ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                            .content(nonNull(m.getContent()))
                            .build());
        }
        if (m.getRole() == ChatRole.USER) {
            if (m.hasContentParts()) {
                return ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder()
                                .content(ChatCompletionUserMessageParam.Content
                                        .ofArrayOfContentParts(toContentParts(m)))
                                .build());
            }
            return ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                            .content(nonNull(m.getContent()))
                            .build());
        }
        if (m.getRole() == ChatRole.TOOL) {
            return ChatCompletionMessageParam.ofTool(
                    ChatCompletionToolMessageParam.builder()
                            .toolCallId(m.getToolCallId())
                            .content(nonNull(m.getContent()))
                            .build());
        }
        // assistant
        ChatCompletionAssistantMessageParam.Builder ab = ChatCompletionAssistantMessageParam.builder()
                .content(nonNull(m.getContent()));
        if (m.getReasoning() != null) {
            // DeepSeek 等要求 thinking 模式下把 reasoning_content 原样回传
            ab.putAdditionalProperty("reasoning_content", JsonValue.from(m.getReasoning()));
        }
        if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            List<ChatCompletionMessageToolCall> calls =
                    new ArrayList<ChatCompletionMessageToolCall>();
            for (ToolCall tc : m.getToolCalls()) {
                calls.add(ChatCompletionMessageToolCall.ofFunction(
                        ChatCompletionMessageFunctionToolCall.builder()
                                .id(tc.getId())
                                .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                        .name(tc.getName())
                                        .arguments(tc.getArgumentsJson())
                                        .build())
                                .build()));
            }
            ab.toolCalls(calls);
        }
        return ChatCompletionMessageParam.ofAssistant(ab.build());
    }

    /** OpenAI 各消息角色要求 content 非空；统一 null 归一化为空串。 */
    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static List<ChatCompletionContentPart> toContentParts(ChatMessage m) {
        List<ChatCompletionContentPart> parts = new ArrayList<ChatCompletionContentPart>();
        for (ContentPart p : m.getContentParts()) {
            if (p.getType() == ContentPart.Type.TEXT) {
                parts.add(ChatCompletionContentPart.ofText(
                        ChatCompletionContentPartText.builder().text(p.getText()).build()));
            } else {
                parts.add(ChatCompletionContentPart.ofImageUrl(
                        ChatCompletionContentPartImage.builder()
                                .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                        .url(p.getImageUrl())
                                        .build())
                                .build()));
            }
        }
        return parts;
    }

    private ChatCompletionTool toTool(ToolDefinition t) {
        FunctionDefinition.Builder fb = FunctionDefinition.builder()
                .name(t.getName());
        if (t.getDescription() != null) {
            fb.description(t.getDescription());
        }
        fb.parameters(toFunctionParameters(t.getParametersJson()));
        return ChatCompletionTool.ofFunction(
                ChatCompletionFunctionTool.builder().function(fb.build()).build());
    }

    private static FunctionParameters toFunctionParameters(String parametersJson) {
        FunctionParameters.Builder b = FunctionParameters.builder();
        try {
            JsonNode node = Json.readTree(parametersJson);
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    b.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue()));
                }
            }
        } catch (Exception ignored) {
            // 参数 JSON 无效时使用空对象
        }
        return b.build();
    }

    private static ChatCompletionToolChoiceOption toToolChoice(ChatRequest request) {
        switch (request.getToolChoice()) {
            case NONE:
                return ChatCompletionToolChoiceOption.ofAuto(
                        ChatCompletionToolChoiceOption.Auto.NONE);
            case REQUIRED:
                return ChatCompletionToolChoiceOption.ofAuto(
                        ChatCompletionToolChoiceOption.Auto.REQUIRED);
            case FUNCTION:
                return ChatCompletionToolChoiceOption.ofNamedToolChoice(
                        ChatCompletionNamedToolChoice.builder()
                                .function(ChatCompletionNamedToolChoice.Function.builder()
                                        .name(request.getToolChoiceFunction())
                                        .build())
                                .build());
            case AUTO:
            default:
                return ChatCompletionToolChoiceOption.ofAuto(
                        ChatCompletionToolChoiceOption.Auto.AUTO);
        }
    }

    private static void applyResponseFormat(
            ChatCompletionCreateParams.Body.Builder cb, ChatRequest request) {
        if (request.getResponseFormat()
                == ResponseFormat.JSON_OBJECT) {
            cb.responseFormat(ResponseFormatJsonObject.builder()
                    .type(JsonValue.from("json_object"))
                    .build());
            return;
        }
        if (request.getResponseFormat()
                == ResponseFormat.JSON_SCHEMA) {
            ResponseFormatJsonSchema.JsonSchema.Builder jsBuilder =
                    ResponseFormatJsonSchema.JsonSchema.builder()
                    .name(request.getResponseFormatName() == null
                            ? "response_schema"
                            : request.getResponseFormatName());
            ResponseFormatJsonSchema.JsonSchema.Schema.Builder sb =
                    ResponseFormatJsonSchema.JsonSchema.Schema.builder();
            try {
                JsonNode node = Json.readTree(request.getResponseFormatSchema());
                if (node.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> e = fields.next();
                        sb.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue()));
                    }
                }
            } catch (Exception ignored) {
                // 无效 schema 时发送空对象
            }
            jsBuilder.schema(sb.build());
            cb.responseFormat(ResponseFormatJsonSchema.builder()
                    .type(JsonValue.from("json_schema"))
                    .jsonSchema(jsBuilder.build())
                    .build());
        }
    }

    private ChatResponse toResponse(ChatCompletion c, String model) {
        String content = null;
        String reasoning = null;
        List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        FinishReason fr = null;

        if (!c.choices().isEmpty()) {
            ChatCompletion.Choice choice = c.choices().get(0);
            ChatCompletionMessage msg = choice.message();
            if (msg.content().isPresent()) {
                content = msg.content().get();
            }
            reasoning = reasoningContent(msg._additionalProperties());
            if (msg.toolCalls().isPresent()) {
                for (ChatCompletionMessageToolCall tc : msg.toolCalls().get()) {
                    if (tc.isFunction()) {
                        ChatCompletionMessageFunctionToolCall f = tc.asFunction();
                        toolCalls.add(new ToolCall(
                                f.id(), f.function().name(), f.function().arguments()));
                    }
                }
            }
            fr = toFinishReason(choice.finishReason());
        }

        Usage usage = null;
        if (c.usage().isPresent()) {
            CompletionUsage u = c.usage().get();
            usage = new Usage(u.promptTokens(), u.completionTokens(), u.totalTokens());
        }
        return ChatResponse.builder()
                .content(content)
                .reasoning(reasoning)
                .toolCalls(toolCalls)
                .finishReason(fr)
                .usage(usage)
                .provider("openai-chat")
                .model(model)
                .id(c.id())
                .build();
    }

    /**
     * 从 SDK 未识别的额外字段中提取思考内容（DeepSeek 等兼容端点通过
     * reasoning_content 返回推理过程）。
     */
    private static String reasoningContent(
            java.util.Map<String, com.openai.core.JsonValue> additionalProperties) {
        if (additionalProperties == null) {
            return null;
        }
        com.openai.core.JsonValue value = additionalProperties.get("reasoning_content");
        if (value == null) {
            return null;
        }
        java.util.Optional<?> opt = value.asString();
        return opt.isPresent() && opt.get() instanceof String
                ? (String) opt.get()
                : null;
    }

    private static FinishReason toFinishReason(ChatCompletion.Choice.FinishReason fr) {
        return FinishReason.fromWire(fr.value().name().toLowerCase().replace('_', '-'));
    }

    private static FinishReason toFinishReason(ChatCompletionChunk.Choice.FinishReason fr) {
        return FinishReason.fromWire(fr.value().name().toLowerCase().replace('_', '-'));
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
        return "OpenAIChatCompletionsModel{model='" + defaultModel + "'}";
    }
}
