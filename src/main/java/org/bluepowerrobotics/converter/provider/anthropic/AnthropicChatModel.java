package org.bluepowerrobotics.converter.provider.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.UrlImageSource;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.bluepowerrobotics.converter.core.ChatChunk;
import org.bluepowerrobotics.converter.core.ChatMessage;
import org.bluepowerrobotics.converter.core.ChatModel;
import org.bluepowerrobotics.converter.core.ChatRequest;
import org.bluepowerrobotics.converter.core.ChatResponse;
import org.bluepowerrobotics.converter.core.ChatRole;
import org.bluepowerrobotics.converter.core.ChatStreamListener;
import org.bluepowerrobotics.converter.core.ContentPart;
import org.bluepowerrobotics.converter.core.FinishReason;
import org.bluepowerrobotics.converter.core.ToolChoice;
import org.bluepowerrobotics.converter.core.ToolCall;
import org.bluepowerrobotics.converter.core.ToolDefinition;
import org.bluepowerrobotics.converter.core.Usage;
import org.bluepowerrobotics.converter.provider.ProviderConfig;
import org.bluepowerrobotics.converter.util.ToolCallAccumulator;
import org.bluepowerrobotics.converter.util.Json;

/**
 * Anthropic Messages API 适配器，基于官方 com.anthropic:anthropic-java。
 */
public final class AnthropicChatModel implements ChatModel {

    private static final long DEFAULT_MAX_TOKENS = 4096L;

    private final AnthropicClient client;
    private final String defaultModel;
    private final String defaultApiKey;

    public AnthropicChatModel(ProviderConfig config) {
        AnthropicOkHttpClient.Builder ob = AnthropicOkHttpClient.builder();
        if (config.getBaseUrl() != null && !config.getBaseUrl().trim().isEmpty()) {
            ob.baseUrl(config.getBaseUrl().trim());
        }
        this.client = ob.build();
        this.defaultModel = config.getModel();
        this.defaultApiKey = config.getApiKey();
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        try {
            MessageCreateParams params = buildParams(request);
            Message message = client.messages().create(withAuth(params, request));
            return toResponse(message, effectiveModel(request));
        } catch (Exception e) {
            throw new IllegalStateException("Anthropic messages call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(ChatRequest request, ChatStreamListener listener) {
        try {
            MessageCreateParams params = buildParams(request);
            StreamResponse<RawMessageStreamEvent> sr =
                    client.messages().createStreaming(withAuth(params, request));
            java.util.Map<Long, ToolCallAccumulator> toolCallAccumulators =
                    new java.util.LinkedHashMap<Long, ToolCallAccumulator>();
            try (java.util.stream.Stream<RawMessageStreamEvent> stream = sr.stream()) {
                Iterator<RawMessageStreamEvent> it = stream.iterator();
                while (it.hasNext()) {
                    RawMessageStreamEvent ev = it.next();
                    if (ev.isContentBlockStart()) {
                        RawContentBlockStartEvent start = ev.asContentBlockStart();
                        RawContentBlockStartEvent.ContentBlock block = start.contentBlock();
                        if (block.isToolUse()) {
                            ToolUseBlock toolUse = block.asToolUse();
                            ToolCallAccumulator acc = new ToolCallAccumulator();
                            acc.id = toolUse.id();
                            acc.name = toolUse.name();
                            toolCallAccumulators.put(start.index(), acc);
                        }
                    } else if (ev.isContentBlockDelta()) {
                        RawContentBlockDeltaEvent deltaEvent = ev.asContentBlockDelta();
                        RawContentBlockDelta delta = ev.asContentBlockDelta().delta();
                        if (delta.isText()) {
                            listener.onChunk(new ChatChunk(delta.asText().text(), null));
                        } else if (delta.isThinking()) {
                            listener.onChunk(new ChatChunk(null, delta.asThinking().thinking(), null));
                        } else if (delta.isInputJson()) {
                            ToolCallAccumulator acc = toolCallAccumulators.get(deltaEvent.index());
                            if (acc != null) {
                                acc.args.append(delta.asInputJson().partialJson());
                            }
                        }
                    } else if (ev.isMessageStop()) {
                        listener.onChunk(new ChatChunk(null, FinishReason.STOP));
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

    private MessageCreateParams buildParams(ChatRequest request) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(effectiveModel(request))
                .maxTokens(request.getMaxTokens() == null
                        ? DEFAULT_MAX_TOKENS
                        : request.getMaxTokens().longValue());

        StringBuilder system = null;
        List<MessageParam> messages = new ArrayList<MessageParam>();
        for (ChatMessage m : request.getMessages()) {
            if (m.getRole() == ChatRole.SYSTEM) {
                if (system == null) {
                    system = new StringBuilder();
                }
                if (system.length() > 0) {
                    system.append('\n');
                }
                system.append(m.getContent());
                continue;
            }
            messages.add(toMessageParam(m));
        }
        if (system != null) {
            b.system(system.toString());
        }
        b.messages(messages);

        for (ToolDefinition t : request.getTools()) {
            b.addTool(ToolUnion.ofTool(toTool(t)));
        }
        if (request.getTemperature() != null) {
            b.temperature(request.getTemperature());
        }
        if (request.getToolChoice() != null) {
            b.toolChoice(toToolChoice(request));
        }
        return b.build();
    }

    private MessageCreateParams withAuth(MessageCreateParams params, ChatRequest request) {
        String key = request.getApiKey() != null ? request.getApiKey() : defaultApiKey;
        if (key == null) {
            return params;
        }
        return params.toBuilder()
                .putAdditionalHeader("x-api-key", key)
                .build();
    }

    private static com.anthropic.models.messages.ToolChoice toToolChoice(ChatRequest request) {
        switch (request.getToolChoice()) {
            case NONE:
                return com.anthropic.models.messages.ToolChoice.ofNone(
                        ToolChoiceNone.builder().build());
            case REQUIRED:
                return com.anthropic.models.messages.ToolChoice.ofAny(
                        ToolChoiceAny.builder().build());
            case FUNCTION:
                return com.anthropic.models.messages.ToolChoice.ofTool(
                        ToolChoiceTool.builder().name(request.getToolChoiceFunction()).build());
            case AUTO:
            default:
                return com.anthropic.models.messages.ToolChoice.ofAuto(
                        ToolChoiceAuto.builder().build());
        }
    }

    private MessageParam toMessageParam(ChatMessage m) {
        if (m.getRole() == ChatRole.USER) {
            if (m.hasContentParts()) {
                List<ContentBlockParam> blocks = new ArrayList<ContentBlockParam>();
                for (ContentPart p : m.getContentParts()) {
                    if (p.getType() == ContentPart.Type.TEXT) {
                        blocks.add(ContentBlockParam.ofText(
                                TextBlockParam.builder().text(p.getText()).build()));
                    } else {
                        blocks.add(ContentBlockParam.ofImage(toImageBlock(p)));
                    }
                }
                return MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(MessageParam.Content.ofBlockParams(blocks))
                        .build();
            }
            if (m.getToolCallId() == null) {
                return MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(nonNull(m.getContent()))
                        .build();
            }
            // tool 结果：Anthropic 用带 tool_result 块的 user 消息表达
            ContentBlockParam result = ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                            .toolUseId(m.getToolCallId())
                            .content(nonNull(m.getContent()))
                            .build());
            return MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(
                            Collections.<ContentBlockParam>singletonList(result)))
                    .build();
        }
        if (m.getRole() == ChatRole.TOOL) {
            // 保险起见：tool 角色转换为 tool_result user 消息
            ContentBlockParam result = ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                            .toolUseId(m.getToolCallId())
                            .content(nonNull(m.getContent()))
                            .build());
            return MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(
                            Collections.<ContentBlockParam>singletonList(result)))
                    .build();
        }
        // assistant
        if (m.getToolCalls() == null || m.getToolCalls().isEmpty()) {
            return MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .content(nonNull(m.getContent()))
                    .build();
        }
        List<ContentBlockParam> blocks = new ArrayList<ContentBlockParam>();
        if (m.getContent() != null && !m.getContent().isEmpty()) {
            blocks.add(ContentBlockParam.ofText(
                    TextBlockParam.builder().text(m.getContent()).build()));
        }
        for (ToolCall tc : m.getToolCalls()) {
            blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(tc.getId())
                    .name(tc.getName())
                    .input(toToolInput(tc.getArgumentsJson()))
                    .build()));
        }
        return MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .content(MessageParam.Content.ofBlockParams(blocks))
                .build();
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static ImageBlockParam toImageBlock(ContentPart p) {
        ImageBlockParam.Builder b = ImageBlockParam.builder();
        if (p.isImageDataAvailable()) {
            b.source(ImageBlockParam.Source.ofBase64(
                    Base64ImageSource.builder()
                            .type(JsonValue.from("base64"))
                            .mediaType(Base64ImageSource.MediaType.of(p.getMimeType()))
                            .data(Base64.getEncoder().encodeToString(p.getImageData()))
                            .build()));
        } else {
            b.source(ImageBlockParam.Source.ofUrl(
                    UrlImageSource.builder()
                            .type(JsonValue.from("url"))
                            .url(p.getImageUrl())
                            .build()));
        }
        return b.build();
    }

    private static Tool toTool(ToolDefinition t) {
        Tool.Builder b = Tool.builder().name(t.getName());
        if (t.getDescription() != null) {
            b.description(t.getDescription());
        }
        Tool.InputSchema.Builder sb = Tool.InputSchema.builder();
        try {
            JsonNode node = Json.readTree(t.getParametersJson());
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    sb.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue()));
                }
            }
        } catch (Exception ignored) {
            // 参数 JSON 无效时使用空对象
        }
        return b.inputSchema(sb.build()).build();
    }

    private static ToolUseBlockParam.Input toToolInput(String argumentsJson) {
        ToolUseBlockParam.Input.Builder ib = ToolUseBlockParam.Input.builder();
        try {
            JsonNode node = Json.readTree(argumentsJson);
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    ib.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue()));
                }
            }
        } catch (Exception ignored) {
            // 参数 JSON 无效时使用空对象
        }
        return ib.build();
    }

    private ChatResponse toResponse(Message message, String model) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<ToolCall>();

        for (ContentBlock block : message.content()) {
            if (block.isText()) {
                TextBlock tb = block.asText();
                text.append(tb.text());
            } else if (block.isThinking()) {
                reasoning.append(block.asThinking().thinking());
            } else if (block.isToolUse()) {
                ToolUseBlock tu = block.asToolUse();
                toolCalls.add(new ToolCall(tu.id(), tu.name(), tu._input().toString()));
            }
        }

        FinishReason fr = null;
        if (message.stopReason().isPresent()) {
            fr = toFinishReason(message.stopReason().get());
        }

        com.anthropic.models.messages.Usage u = message.usage();
        Usage usage = new Usage(u.inputTokens(), u.outputTokens(), null);
        return ChatResponse.builder()
                .content(text.toString())
                .reasoning(reasoning.length() == 0 ? null : reasoning.toString())
                .toolCalls(toolCalls)
                .finishReason(fr)
                .usage(usage)
                .provider("anthropic")
                .model(model)
                .id(message.id())
                .build();
    }

    private static FinishReason toFinishReason(StopReason sr) {
        switch (sr.value()) {
            case END_TURN:
            case STOP_SEQUENCE:
                return FinishReason.STOP;
            case MAX_TOKENS:
                return FinishReason.LENGTH;
            case TOOL_USE:
                return FinishReason.TOOL_CALLS;
            case REFUSAL:
                return FinishReason.CONTENT_FILTER;
            default:
                return FinishReason.OTHER;
        }
    }

    private String effectiveModel(ChatRequest request) {
        return request.getModel() != null ? request.getModel() : defaultModel;
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public String toString() {
        return "AnthropicChatModel{model='" + defaultModel + "'}";
    }
}
