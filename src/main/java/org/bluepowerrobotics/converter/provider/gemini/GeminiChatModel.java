package org.bluepowerrobotics.converter.provider.gemini;

import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Blob;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Google Gemini 适配器，基于官方 com.google.genai:google-genai SDK。
 *
 * <p>说明：Google Interactions API 已于 2026-06 GA，但官方 Java SDK 目前仍通过
 * generateContent/generateContentStream 暴露同等的对话能力（Java 侧的 Interactions 支持
 * 尚未发布）。这里使用官方 SDK 的 GenerateContent 接口，API key 与自定义 baseUrl 均可配置。
 */
public final class GeminiChatModel implements ChatModel {

    private final String baseUrl;
    private final String defaultModel;
    private final String defaultApiKey;
    private final Map<String, Client> clientsByKey = new ConcurrentHashMap<String, Client>();

    public GeminiChatModel(ProviderConfig config) {
        this.baseUrl = config.getBaseUrl();
        this.defaultModel = config.getModel();
        this.defaultApiKey = config.getApiKey();
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        try {
            BuildResult built = build(request);
            GenerateContentResponse response = clientFor(request).models.generateContent(
                    effectiveModel(request), built.contents, built.config);
            return toResponse(response, effectiveModel(request));
        } catch (Exception e) {
            throw new IllegalStateException("Gemini generateContent failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(ChatRequest request, ChatStreamListener listener) {
        try {
            BuildResult built = build(request);
            ResponseStream<GenerateContentResponse> stream =
                    clientFor(request).models.generateContentStream(
                            effectiveModel(request), built.contents, built.config);
            List<ToolCall> toolCalls = new ArrayList<ToolCall>();
            Iterator<GenerateContentResponse> it = stream.iterator();
            while (it.hasNext()) {
                GenerateContentResponse chunk = it.next();
                if (!chunk.candidates().isPresent() || chunk.candidates().get().isEmpty()) {
                    continue;
                }
                Candidate candidate = chunk.candidates().get().get(0);
                if (candidate.content().isPresent()
                        && candidate.content().get().parts().isPresent()) {
                    for (Part part : candidate.content().get().parts().get()) {
                        if (part.text().isPresent()) {
                            if (part.thought().isPresent() && part.thought().get()) {
                                listener.onChunk(new ChatChunk(null, part.text().get(), null));
                            } else {
                                listener.onChunk(new ChatChunk(part.text().get(), null));
                            }
                        } else if (part.functionCall().isPresent()) {
                            FunctionCall fc = part.functionCall().get();
                            toolCalls.add(new ToolCall(
                                    null,
                                    fc.name().isPresent() ? fc.name().get() : null,
                                    fc.args().isPresent()
                                            ? Json.toJson(fc.args().get())
                                            : "{}"));
                        }
                    }
                }
                if (candidate.finishReason().isPresent()) {
                    listener.onChunk(new ChatChunk(
                            null, toFinishReason(candidate.finishReason().get())));
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

    private BuildResult build(ChatRequest request) throws IOException {
        GenerateContentConfig.Builder cb = GenerateContentConfig.builder();
        StringBuilder system = null;
        List<Content> contents = new ArrayList<Content>();
        Map<String, String> toolCallNames = new HashMap<String, String>();

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
            if (m.getRole() == ChatRole.TOOL) {
                String name = toolCallNames.get(m.getToolCallId());
                if (name == null) {
                    name = "unknown_function";
                }
                contents.add(Content.builder()
                        .role("user")
                        .parts(Part.builder()
                                .functionResponse(FunctionResponse.builder()
                                        .name(name)
                                        .response(parseObject(m.getContent()))
                                        .build())
                                .build())
                        .build());
                continue;
            }
            if (m.getRole() == ChatRole.ASSISTANT) {
                List<Part> parts = new ArrayList<Part>();
                if (m.getContent() != null && !m.getContent().isEmpty()) {
                    parts.add(Part.builder().text(m.getContent()).build());
                }
                for (ToolCall tc : m.getToolCalls()) {
                    toolCallNames.put(tc.getId(), tc.getName());
                    parts.add(Part.builder()
                            .functionCall(FunctionCall.builder()
                                    .name(tc.getName())
                                    .args(parseObject(tc.getArgumentsJson()))
                                    .build())
                            .build());
                }
                contents.add(Content.builder().role("model").parts(parts).build());
                continue;
            }
            // user
            contents.add(Content.builder().role("user").parts(toParts(m)).build());
        }
        if (system != null) {
            cb.systemInstruction(Content.builder()
                    .role("user")
                    .parts(Part.builder().text(system.toString()).build())
                    .build());
        }
        if (request.getTemperature() != null) {
            cb.temperature(request.getTemperature().floatValue());
        }
        if (request.getMaxTokens() != null) {
            cb.maxOutputTokens(request.getMaxTokens());
        }
        if (request.getTopP() != null) {
            cb.topP(request.getTopP().floatValue());
        }
        if (!request.getStop().isEmpty()) {
            cb.stopSequences(request.getStop());
        }
        if (!request.getTools().isEmpty()) {
            List<Tool> tools = new ArrayList<Tool>();
            tools.add(Tool.builder().functionDeclarations(toFunctionDeclarations(request)).build());
            cb.tools(tools);
        }
        if (request.getToolChoice() != null) {
            cb.toolConfig(toToolConfig(request));
        }
        if (request.getResponseFormat() != null) {
            if (request.getResponseFormat()
                    == org.bluepowerrobotics.converter.core.ResponseFormat.JSON_OBJECT) {
                cb.responseMimeType("application/json");
            } else if (request.getResponseFormat()
                    == org.bluepowerrobotics.converter.core.ResponseFormat.JSON_SCHEMA) {
                cb.responseMimeType("application/json");
                if (request.getResponseFormatSchema() != null) {
                    cb.responseSchema(Schema.fromJson(request.getResponseFormatSchema()));
                }
            }
        }
        return new BuildResult(contents, cb.build());
    }

    private static List<Part> toParts(ChatMessage m) throws IOException {
        List<Part> parts = new ArrayList<Part>();
        if (!m.hasContentParts()) {
            if (m.getContent() != null) {
                parts.add(Part.builder().text(m.getContent()).build());
            }
            return parts;
        }
        for (ContentPart p : m.getContentParts()) {
            if (p.getType() == ContentPart.Type.TEXT) {
                if (p.getText() != null) {
                    parts.add(Part.builder().text(p.getText()).build());
                }
            } else {
                Blob blob = toBlob(p);
                if (blob != null) {
                    parts.add(Part.builder().inlineData(blob).build());
                } else {
                    System.err.println("[Gemini] 无法加载图片，已跳过: " + p.getImageUrl());
                }
            }
        }
        return parts;
    }

    private static Blob toBlob(ContentPart p) throws IOException {
        if (p.isImageDataAvailable()) {
            return Blob.builder()
                    .mimeType(p.getMimeType() == null ? "image/jpeg" : p.getMimeType())
                    .data(p.getImageData())
                    .build();
        }
        String url = p.getImageUrl();
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            byte[] bytes = fetchBytes(url);
            return Blob.builder()
                    .mimeType(guessMimeType(url))
                    .data(bytes)
                    .build();
        }
        return null;
    }

    private static byte[] fetchBytes(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "AIAPIConverter/1.0");
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("图片下载失败 HTTP " + code + ": " + url);
        }
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 10 * 1024 * 1024) {
                    throw new IOException("图片超过 10MB: " + url);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static String guessMimeType(String url) {
        String lower = url.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static List<FunctionDeclaration> toFunctionDeclarations(ChatRequest request) {
        List<FunctionDeclaration> out = new ArrayList<FunctionDeclaration>();
        for (ToolDefinition t : request.getTools()) {
            FunctionDeclaration.Builder b = FunctionDeclaration.builder().name(t.getName());
            if (t.getDescription() != null) {
                b.description(t.getDescription());
            }
            try {
                b.parameters(Schema.fromJson(t.getParametersJson()));
            } catch (Exception ignored) {
                b.parameters(Schema.builder().type(
                        new com.google.genai.types.Type(
                                com.google.genai.types.Type.Known.OBJECT)).build());
            }
            out.add(b.build());
        }
        return out;
    }

    private static ToolConfig toToolConfig(ChatRequest request) {
        FunctionCallingConfig.Builder b = FunctionCallingConfig.builder();
        switch (request.getToolChoice()) {
            case NONE:
                b.mode(new FunctionCallingConfigMode(FunctionCallingConfigMode.Known.NONE));
                break;
            case REQUIRED:
                b.mode(new FunctionCallingConfigMode(FunctionCallingConfigMode.Known.ANY));
                break;
            case FUNCTION:
                b.mode(new FunctionCallingConfigMode(FunctionCallingConfigMode.Known.ANY));
                if (request.getToolChoiceFunction() != null) {
                    b.allowedFunctionNames(request.getToolChoiceFunction());
                }
                break;
            case AUTO:
            default:
                b.mode(new FunctionCallingConfigMode(FunctionCallingConfigMode.Known.AUTO));
                break;
        }
        return ToolConfig.builder().functionCallingConfig(b).build();
    }

    private static Map<String, Object> parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new HashMap<String, Object>();
        }
        try {
            Object v = Json.MAPPER.readValue(json, Object.class);
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) v;
                return map;
            }
            return new HashMap<String, Object>();
        } catch (Exception e) {
            return new HashMap<String, Object>();
        }
    }

    private ChatResponse toResponse(GenerateContentResponse r, String model) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        FinishReason fr = null;
        if (r.candidates().isPresent() && !r.candidates().get().isEmpty()) {
            Candidate candidate = r.candidates().get().get(0);
            if (candidate.content().isPresent()
                    && candidate.content().get().parts().isPresent()) {
                for (Part part : candidate.content().get().parts().get()) {
                    if (part.text().isPresent()) {
                        if (part.thought().isPresent() && part.thought().get()) {
                            reasoning.append(part.text().get());
                        } else {
                            text.append(part.text().get());
                        }
                    } else if (part.functionCall().isPresent()) {
                        FunctionCall fc = part.functionCall().get();
                        toolCalls.add(new ToolCall(
                                null,
                                fc.name().orElse(null),
                                fc.args().isPresent()
                                        ? Json.toJson(fc.args().get())
                                        : "{}"));
                    }
                }
            }
            if (candidate.finishReason().isPresent()) {
                fr = toFinishReason(candidate.finishReason().get());
            }
        }
        Usage usage = null;
        if (r.usageMetadata().isPresent()) {
            GenerateContentResponseUsageMetadata u = r.usageMetadata().get();
            usage = new Usage(
                    u.promptTokenCount().isPresent() ? u.promptTokenCount().get().longValue() : null,
                    u.candidatesTokenCount().isPresent()
                            ? u.candidatesTokenCount().get().longValue()
                            : null,
                    u.totalTokenCount().isPresent() ? u.totalTokenCount().get().longValue() : null);
        }
        return ChatResponse.builder()
                .content(text.toString())
                .reasoning(reasoning.length() == 0 ? null : reasoning.toString())
                .toolCalls(toolCalls)
                .finishReason(fr)
                .usage(usage)
                .provider("gemini")
                .model(model)
                .build();
    }

    private static FinishReason toFinishReason(com.google.genai.types.FinishReason fr) {
        String v = fr.toString();
        if ("STOP".equalsIgnoreCase(v)) {
            return FinishReason.STOP;
        }
        if ("MAX_TOKENS".equalsIgnoreCase(v)) {
            return FinishReason.LENGTH;
        }
        return FinishReason.OTHER;
    }

    private String effectiveModel(ChatRequest request) {
        return request.getModel() != null ? request.getModel() : defaultModel;
    }

    private Client clientFor(ChatRequest request) {
        String key = request.getApiKey() != null ? request.getApiKey() : defaultApiKey;
        String cacheKey = key == null ? "" : key;
        Client cached = clientsByKey.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Client created = buildClient(key);
        if (clientsByKey.size() >= 16) {
            for (Client old : clientsByKey.values()) {
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

    private Client buildClient(String apiKey) {
        Client.Builder builder = new Client.Builder();
        if (apiKey != null) {
            builder.apiKey(apiKey);
        }
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            builder.httpOptions(HttpOptions.builder().baseUrl(baseUrl.trim()).build());
        }
        return builder.build();
    }

    @Override
    public void close() {
        for (Client cached : clientsByKey.values()) {
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
        return "GeminiChatModel{model='" + defaultModel + "'}";
    }

    private static final class BuildResult {
        final List<Content> contents;
        final GenerateContentConfig config;

        BuildResult(List<Content> contents, GenerateContentConfig config) {
            this.contents = contents;
            this.config = config;
        }
    }
}
