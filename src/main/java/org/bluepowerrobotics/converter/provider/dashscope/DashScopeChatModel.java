package org.bluepowerrobotics.converter.provider.dashscope;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MessageContentBase;
import com.alibaba.dashscope.common.MessageContentImageURL;
import com.alibaba.dashscope.common.MessageContentText;
import com.alibaba.dashscope.common.ImageURL;
import com.alibaba.dashscope.common.ResponseFormat;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolChoice;
import com.alibaba.dashscope.tools.ToolFunction;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.reactivex.Flowable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bluepowerrobotics.converter.core.ChatChunk;
import org.bluepowerrobotics.converter.core.ChatMessage;
import org.bluepowerrobotics.converter.core.ChatModel;
import org.bluepowerrobotics.converter.core.ChatRequest;
import org.bluepowerrobotics.converter.core.ChatResponse;
import org.bluepowerrobotics.converter.core.ChatStreamListener;
import org.bluepowerrobotics.converter.core.ContentPart;
import org.bluepowerrobotics.converter.core.FinishReason;
import org.bluepowerrobotics.converter.core.ToolCall;
import org.bluepowerrobotics.converter.core.ToolDefinition;
import org.bluepowerrobotics.converter.core.Usage;
import org.bluepowerrobotics.converter.provider.ProviderConfig;

/**
 * DashScope（阿里云百炼）适配器，基于官方 com.alibaba:dashscope-sdk-java。
 * 对应 Qwen 系列模型。
 */
public final class DashScopeChatModel implements ChatModel {

    private final String apiKey;
    private final String defaultModel;
    private final Generation generation;

    public DashScopeChatModel(ProviderConfig config) {
        this.apiKey = config.getApiKey();
        this.defaultModel = config.getModel();
        if (config.getBaseUrl() != null && !config.getBaseUrl().trim().isEmpty()) {
            this.generation = new Generation("http", config.getBaseUrl().trim());
        } else {
            this.generation = new Generation();
        }
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        try {
            GenerationResult result = generation.call(buildParam(request, false));
            return toResponse(result, effectiveModel(request));
        } catch (Exception e) {
            throw new IllegalStateException("DashScope generation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(ChatRequest request, ChatStreamListener listener) {
        try {
            Flowable<GenerationResult> flowable = generation.streamCall(buildParam(request, true));
            List<ToolCall> toolCalls = new ArrayList<ToolCall>();
            for (GenerationResult chunk : flowable.blockingIterable()) {
                String delta = extractContent(chunk);
                FinishReason fr = extractFinishReason(chunk);
                List<ToolCall> calls = extractToolCalls(chunk);
                if (calls != null) {
                    toolCalls.addAll(calls);
                }
                if (delta != null && !delta.isEmpty()) {
                    listener.onChunk(new ChatChunk(delta, null));
                }
                if (fr != null) {
                    listener.onChunk(new ChatChunk(null, fr));
                }
            }
            if (!toolCalls.isEmpty()) {
                listener.onChunk(new ChatChunk(null, null, toolCalls, FinishReason.TOOL_CALLS));
            }
            listener.onDone();
        } catch (Throwable t) {
            listener.onError(t);
        }
    }

    private GenerationParam buildParam(ChatRequest request, boolean streaming) {
        List<Message> messages = new ArrayList<Message>();
        for (ChatMessage m : request.getMessages()) {
            messages.add(toDashScopeMessage(m));
        }

        GenerationParam.GenerationParamBuilder<?, ?> b = GenerationParam.builder()
                .model(effectiveModel(request))
                .apiKey(effectiveApiKey(request))
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE);

        if (request.getTemperature() != null) {
            b.temperature(request.getTemperature().floatValue());
        }
        if (request.getMaxTokens() != null) {
            b.maxTokens(request.getMaxTokens());
        }
        if (request.getTopP() != null) {
            b.topP(request.getTopP());
        }
        for (String s : request.getStop()) {
            b.stopString(s);
        }
        if (!request.getTools().isEmpty()) {
            b.tools(toDashScopeTools(request.getTools()));
        }
        if (request.getToolChoice() != null) {
            b.toolChoice(toDashScopeToolChoice(request));
        }
        if (request.getResponseFormat() != null) {
            b.responseFormat(toDashScopeResponseFormat(request));
        }
        if (streaming) {
            b.incrementalOutput(true);
        }
        return b.build();
    }

    private String effectiveModel(ChatRequest request) {
        return request.getModel() != null ? request.getModel() : defaultModel;
    }

    private String effectiveApiKey(ChatRequest request) {
        return request.getApiKey() != null ? request.getApiKey() : apiKey;
    }

    private Message toDashScopeMessage(ChatMessage m) {
        Message.MessageBuilder<?, ?> b = Message.builder()
                .role(m.getRole().wire())
                .content(m.getContent() == null ? "" : m.getContent());
        if (m.hasContentParts()) {
            List<MessageContentBase> contents = new ArrayList<MessageContentBase>();
            for (ContentPart part : m.getContentParts()) {
                if (part.getType() == ContentPart.Type.TEXT) {
                    contents.add(MessageContentText.builder().text(part.getText()).build());
                } else {
                    contents.add(MessageContentImageURL.builder()
                            .imageURL(ImageURL.builder().url(part.getImageUrl()).build())
                            .build());
                }
            }
            b.contents(contents);
        }
        if (m.getName() != null) {
            b.name(m.getName());
        }
        if (m.getToolCallId() != null) {
            b.toolCallId(m.getToolCallId());
        }
        if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            List<ToolCallBase> calls = new ArrayList<ToolCallBase>();
            for (ToolCall tc : m.getToolCalls()) {
                ToolCallFunction f = new ToolCallFunction();
                f.setId(tc.getId());
                f.setType("function");
                ToolCallFunction.CallFunction fn = f.new CallFunction();
                fn.setName(tc.getName());
                fn.setArguments(tc.getArgumentsJson());
                f.setFunction(fn);
                calls.add(f);
            }
            b.toolCalls(calls);
        }
        return b.build();
    }

    private static Object toDashScopeToolChoice(ChatRequest request) {
        if (request.getToolChoice() == org.bluepowerrobotics.converter.core.ToolChoice.FUNCTION
                && request.getToolChoiceFunction() != null) {
            for (ToolDefinition d : request.getTools()) {
                if (request.getToolChoiceFunction().equals(d.getName())) {
                    return ToolChoice.builder()
                            .strategy("auto")
                            .tool(toDashScopeTool(d))
                            .build();
                }
            }
        }
        String strategy = request.getToolChoice().name().toLowerCase();
        return ToolChoice.builder().strategy(strategy).build();
    }

    private static ResponseFormat toDashScopeResponseFormat(ChatRequest request) {
        if (request.getResponseFormat() == org.bluepowerrobotics.converter.core.ResponseFormat.JSON_OBJECT) {
            return ResponseFormat.from(ResponseFormat.JSON_OBJECT);
        }
        if (request.getResponseFormat() == org.bluepowerrobotics.converter.core.ResponseFormat.JSON_SCHEMA) {
            return ResponseFormat.builder()
                    .type(ResponseFormat.JSON_SCHEMA)
                    .jsonSchema(ResponseFormat.JsonSchemaFormat.builder()
                            .name(request.getResponseFormatName() == null
                                    ? "response_schema"
                                    : request.getResponseFormatName())
                            .schema(parseJsonObject(request.getResponseFormatSchema()))
                            .build())
                    .build();
        }
        return null;
    }

    private static ToolFunction toDashScopeTool(ToolDefinition d) {
        FunctionDefinition fd = FunctionDefinition.builder()
                .name(d.getName())
                .description(d.getDescription())
                .parameters(parseJsonObject(d.getParametersJson()))
                .build();
        return ToolFunction.builder().function(fd).build();
    }

    private List<ToolBase> toDashScopeTools(List<ToolDefinition> defs) {
        List<ToolBase> tools = new ArrayList<ToolBase>();
        for (ToolDefinition d : defs) {
            tools.add(toDashScopeTool(d));
        }
        return tools;
    }

    private static JsonObject parseJsonObject(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "object");
            obj.add("properties", new JsonObject());
            return obj;
        }
    }

    private ChatResponse toResponse(GenerationResult r, String model) {
        String content = null;
        String reasoning = null;
        List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        FinishReason fr = null;

        GenerationOutput output = r.getOutput();
        if (output != null) {
            if (output.getChoices() != null && !output.getChoices().isEmpty()) {
                GenerationOutput.Choice choice = output.getChoices().get(0);
                if (choice.getMessage() != null) {
                    Message msg = choice.getMessage();
                    content = msg.getContent();
                    reasoning = msg.getReasoningContent();
                    if (msg.getToolCalls() != null) {
                        for (ToolCallBase base : msg.getToolCalls()) {
                            if (base instanceof ToolCallFunction) {
                                ToolCallFunction f = (ToolCallFunction) base;
                                ToolCallFunction.CallFunction fn = f.getFunction();
                                toolCalls.add(new ToolCall(
                                        f.getId(),
                                        fn == null ? null : fn.getName(),
                                        fn == null ? null : fn.getArguments()));
                            }
                        }
                    }
                }
                fr = FinishReason.fromWire(choice.getFinishReason());
            } else if (output.getText() != null) {
                content = output.getText();
                fr = FinishReason.fromWire(output.getFinishReason());
            }
        }

        Usage usage = null;
        if (r.getUsage() != null) {
            GenerationUsage u = r.getUsage();
            usage = new Usage(
                    u.getInputTokens() == null ? null : u.getInputTokens().longValue(),
                    u.getOutputTokens() == null ? null : u.getOutputTokens().longValue(),
                    u.getTotalTokens() == null ? null : u.getTotalTokens().longValue());
        }
        return ChatResponse.builder()
                .content(content)
                .reasoning(reasoning)
                .toolCalls(toolCalls)
                .finishReason(fr)
                .usage(usage)
                .provider("dashscope")
                .model(model)
                .id(r.getRequestId())
                .build();
    }

    private String extractContent(GenerationResult chunk) {
        GenerationOutput output = chunk.getOutput();
        if (output == null) {
            return null;
        }
        if (output.getChoices() != null && !output.getChoices().isEmpty()) {
            GenerationOutput.Choice choice = output.getChoices().get(0);
            return choice.getMessage() == null ? null : choice.getMessage().getContent();
        }
        return output.getText();
    }

    private FinishReason extractFinishReason(GenerationResult chunk) {
        GenerationOutput output = chunk.getOutput();
        if (output == null) {
            return null;
        }
        if (output.getChoices() != null && !output.getChoices().isEmpty()) {
            return FinishReason.fromWire(output.getChoices().get(0).getFinishReason());
        }
        return FinishReason.fromWire(output.getFinishReason());
    }

    private List<ToolCall> extractToolCalls(GenerationResult chunk) {
        GenerationOutput output = chunk.getOutput();
        if (output == null || output.getChoices() == null || output.getChoices().isEmpty()) {
            return null;
        }
        Message msg = output.getChoices().get(0).getMessage();
        if (msg == null || msg.getToolCalls() == null) {
            return null;
        }
        List<ToolCall> out = new ArrayList<ToolCall>();
        for (ToolCallBase base : msg.getToolCalls()) {
            if (base instanceof ToolCallFunction) {
                ToolCallFunction f = (ToolCallFunction) base;
                ToolCallFunction.CallFunction fn = f.getFunction();
                out.add(new ToolCall(
                        f.getId(),
                        fn == null ? null : fn.getName(),
                        fn == null ? null : fn.getArguments()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    @Override
    public void close() {
        // DashScope SDK 的同步 Generation 无需显式关闭
    }

    @Override
    public String toString() {
        return "DashScopeChatModel{model='" + defaultModel + "'}";
    }
}
