package org.bluepowerrobotics.converter.provider.openai;

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
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bluepowerrobotics.converter.core.ChatChunk;
import org.bluepowerrobotics.converter.core.ChatMessage;
import org.bluepowerrobotics.converter.core.ChatModel;
import org.bluepowerrobotics.converter.core.ChatRequest;
import org.bluepowerrobotics.converter.core.ChatResponse;
import org.bluepowerrobotics.converter.core.ChatRole;
import org.bluepowerrobotics.converter.core.ChatStreamListener;
import org.bluepowerrobotics.converter.core.ContentPart;
import org.bluepowerrobotics.converter.core.FinishReason;
import org.bluepowerrobotics.converter.core.ToolCall;
import org.bluepowerrobotics.converter.core.ToolDefinition;
import org.bluepowerrobotics.converter.core.Usage;
import org.bluepowerrobotics.converter.provider.ProviderConfig;
import org.bluepowerrobotics.converter.util.Json;

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
            try (java.util.stream.Stream<ChatCompletionChunk> stream = sr.stream()) {
                Iterator<ChatCompletionChunk> it = stream.iterator();
                while (it.hasNext()) {
                    ChatCompletionChunk chunk = it.next();
                    for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                        if (choice.delta().content().isPresent()) {
                            listener.onChunk(new ChatChunk(choice.delta().content().get(), null));
                        }
                        if (choice.finishReason().isPresent()) {
                            listener.onChunk(new ChatChunk(
                                    null, toFinishReason(choice.finishReason().get())));
                        }
                    }
                }
            }
            listener.onDone();
        } catch (Throwable t) {
            listener.onError(t);
        }
    }

    private ChatCompletionCreateParams buildParams(ChatRequest request) {
        ChatCompletionCreateParams.Builder b = ChatCompletionCreateParams.builder()
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
        return b.build();
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
            ChatCompletionCreateParams.Builder cb, ChatRequest request) {
        if (request.getResponseFormat()
                == org.bluepowerrobotics.converter.core.ResponseFormat.JSON_OBJECT) {
            cb.responseFormat(ResponseFormatJsonObject.builder()
                    .type(JsonValue.from("json_object"))
                    .build());
            return;
        }
        if (request.getResponseFormat()
                == org.bluepowerrobotics.converter.core.ResponseFormat.JSON_SCHEMA) {
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
        List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        FinishReason fr = null;

        if (!c.choices().isEmpty()) {
            ChatCompletion.Choice choice = c.choices().get(0);
            ChatCompletionMessage msg = choice.message();
            if (msg.content().isPresent()) {
                content = msg.content().get();
            }
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
                .toolCalls(toolCalls)
                .finishReason(fr)
                .usage(usage)
                .provider("openai-chat")
                .model(model)
                .id(c.id())
                .build();
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
