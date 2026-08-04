package org.bluepowerrobotics.converter.gateway.endpoints;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.bluepowerrobotics.converter.core.ChatMessage;
import org.bluepowerrobotics.converter.core.ChatRequest;
import org.bluepowerrobotics.converter.core.ChatRole;
import org.bluepowerrobotics.converter.core.ContentPart;
import org.bluepowerrobotics.converter.core.ResponseFormat;
import org.bluepowerrobotics.converter.core.ToolCall;
import org.bluepowerrobotics.converter.core.ToolChoice;
import org.bluepowerrobotics.converter.core.ToolDefinition;
import org.bluepowerrobotics.converter.util.Json;

/** 把不同前端 API 的 JSON 请求体转换为统一结构。 */
final class RequestParsing {

    private RequestParsing() {
    }

    /** 提取 OpenAI 风格 content（字符串或内容块数组）中的文本。 */
    static String openAIContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String type = part.path("type").asText("");
                if ("text".equals(type) || "input_text".equals(type)
                        || "output_text".equals(type) || "text_delta".equals(type)) {
                    String t = part.path("text").asText("");
                    if (sb.length() > 0 && !t.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        if (content.isObject() && content.has("text")) {
            return content.get("text").asText();
        }
        return content.asText();
    }

    /**
     * 提取 OpenAI 风格 content 数组中的多模态部件；纯文本或非数组返回 null。
     * 支持 text / image_url / input_text / input_image。
     */
    static List<ContentPart> openAIContentParts(JsonNode content) {
        if (content == null || !content.isArray()) {
            return null;
        }
        List<ContentPart> parts = new ArrayList<ContentPart>();
        boolean found = false;
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if ("text".equals(type) || "input_text".equals(type)
                    || "output_text".equals(type)) {
                parts.add(ContentPart.text(part.path("text").asText("")));
                found = true;
            } else if ("image_url".equals(type)) {
                String url = imageUrl(part.get("image_url"));
                if (url != null) {
                    parts.add(ContentPart.imageUrl(url));
                    found = true;
                }
            } else if ("input_image".equals(type)) {
                String url = imageUrl(part.get("image_url"));
                if (url != null) {
                    parts.add(ContentPart.imageUrl(url));
                    found = true;
                }
            }
        }
        return found ? parts : null;
    }

    private static String imageUrl(JsonNode imageUrlNode) {
        if (imageUrlNode == null || imageUrlNode.isNull()) {
            return null;
        }
        if (imageUrlNode.isTextual()) {
            return imageUrlNode.asText();
        }
        if (imageUrlNode.isObject()) {
            return imageUrlNode.path("url").asText(null);
        }
        return null;
    }

    /** 解析 OpenAI 风格 messages 数组。 */
    static List<ChatMessage> openAIMessages(JsonNode messages) {
        List<ChatMessage> out = new ArrayList<ChatMessage>();
        if (messages == null || !messages.isArray()) {
            return out;
        }
        for (JsonNode m : messages) {
            out.add(openAIMessage(m));
        }
        return out;
    }

    private static ChatMessage openAIMessage(JsonNode m) {
        String role = m.path("role").asText("user");
        String content = openAIContent(m.get("content"));
        if ("system".equals(role)) {
            return ChatMessage.system(content);
        }
        if ("tool".equals(role)) {
            return ChatMessage.tool(m.path("tool_call_id").asText(null), content);
        }
        if ("assistant".equals(role)) {
            ChatMessage.Builder b = ChatMessage.builder().role(ChatRole.ASSISTANT).content(content);
            List<ContentPart> parts = openAIContentParts(m.get("content"));
            if (parts != null) {
                b.contentParts(parts);
            }
            JsonNode calls = m.get("tool_calls");
            if (calls != null && calls.isArray()) {
                for (JsonNode c : calls) {
                    JsonNode fn = c.path("function");
                    b.addToolCall(new ToolCall(
                            c.path("id").asText(null),
                            fn.path("name").asText(null),
                            fn.path("arguments").asText("{}")));
                }
            }
            return b.build();
        }
        List<ContentPart> parts = openAIContentParts(m.get("content"));
        if (parts != null) {
            return ChatMessage.builder()
                    .role(ChatRole.USER)
                    .content(content)
                    .contentParts(parts)
                    .build();
        }
        return ChatMessage.user(content);
    }

    /** 解析 OpenAI 风格 tools 数组（type=function）。 */
    static List<ToolDefinition> openAITools(JsonNode tools) {
        List<ToolDefinition> out = new ArrayList<ToolDefinition>();
        if (tools == null || !tools.isArray()) {
            return out;
        }
        for (JsonNode t : tools) {
            JsonNode fn = t.path("function");
            if (!fn.isMissingNode() && !fn.isNull()) {
                out.add(new ToolDefinition(
                        fn.path("name").asText(null),
                        fn.path("description").asText(null),
                        fn.path("parameters").isMissingNode() || fn.path("parameters").isNull()
                                ? "{\"type\":\"object\",\"properties\":{}}"
                                : fn.get("parameters").toString()));
            }
        }
        return out;
    }

    /** 解析 Anthropic 风格 messages 数组。 */
    static List<ChatMessage> anthropicMessages(JsonNode messages) {
        List<ChatMessage> out = new ArrayList<ChatMessage>();
        if (messages == null || !messages.isArray()) {
            return out;
        }
        for (JsonNode m : messages) {
            out.add(anthropicMessage(m));
        }
        return out;
    }

    private static ChatMessage anthropicMessage(JsonNode m) {
        String role = m.path("role").asText("user");
        JsonNode content = m.get("content");
        if ("assistant".equals(role) && content != null && content.isArray()) {
            ChatMessage.Builder b = ChatMessage.builder().role(ChatRole.ASSISTANT);
            StringBuilder text = new StringBuilder();
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    String t = block.path("text").asText("");
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(t);
                } else if ("tool_use".equals(type)) {
                    JsonNode input = block.get("input");
                    b.addToolCall(new ToolCall(
                            block.path("id").asText(null),
                            block.path("name").asText(null),
                            input == null ? "{}" : input.toString()));
                }
            }
            b.content(text.toString());
            return b.build();
        }
        if ("user".equals(role) && content != null && content.isArray()) {
            for (JsonNode block : content) {
                if ("tool_result".equals(block.path("type").asText(""))) {
                    return ChatMessage.tool(
                            block.path("tool_use_id").asText(null),
                            anthropicToolResult(block.get("content")));
                }
            }
        }
        if (content != null && content.isArray()) {
            List<ContentPart> parts = new ArrayList<ContentPart>();
            boolean hasPart = false;
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    parts.add(ContentPart.text(block.path("text").asText("")));
                    hasPart = true;
                } else if ("image".equals(type)) {
                    ContentPart image = anthropicImage(block.get("source"));
                    if (image != null) {
                        parts.add(image);
                        hasPart = true;
                    }
                }
            }
            if (hasPart) {
                return ChatMessage.builder()
                        .role(ChatRole.fromWire(role))
                        .content(anthropicText(content))
                        .contentParts(parts)
                        .build();
            }
        }
        return ChatMessage.builder()
                .role(ChatRole.fromWire(role))
                .content(anthropicText(content))
                .build();
    }

    private static ContentPart anthropicImage(JsonNode source) {
        if (source == null || !source.isObject()) {
            return null;
        }
        String type = source.path("type").asText("");
        if ("url".equals(type)) {
            String url = source.path("url").asText(null);
            return url == null ? null : ContentPart.imageUrl(url);
        }
        if ("base64".equals(type)) {
            String mediaType = source.path("media_type").asText("application/octet-stream");
            String data = source.path("data").asText(null);
            if (data == null) {
                return null;
            }
            return ContentPart.imageUrl("data:" + mediaType + ";base64," + data);
        }
        return null;
    }

    private static String anthropicToolResult(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String t = part.path("text").asText("");
                if (sb.length() > 0 && !t.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(t);
            }
            return sb.toString();
        }
        return content.toString();
    }

    private static String anthropicText(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText(""))) {
                    String t = part.path("text").asText("");
                    if (sb.length() > 0 && !t.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return content.asText();
    }

    /** 解析 Anthropic 风格 tools 数组。 */
    static List<ToolDefinition> anthropicTools(JsonNode tools) {
        List<ToolDefinition> out = new ArrayList<ToolDefinition>();
        if (tools == null || !tools.isArray()) {
            return out;
        }
        for (JsonNode t : tools) {
            if (!"function".equals(t.path("type").asText(""))) {
                continue;
            }
            JsonNode schema = t.get("input_schema");
            out.add(new ToolDefinition(
                    t.path("name").asText(null),
                    t.path("description").asText(null),
                    schema == null ? "{\"type\":\"object\",\"properties\":{}}" : schema.toString()));
        }
        return out;
    }

    /** 从 JsonNode 提取字符串字段并转为 Double。 */
    static Double doubleField(JsonNode node, String name) {
        JsonNode v = node.get(name);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    /** 从 JsonNode 提取字符串字段并转为 Integer。 */
    static Integer intField(JsonNode node, String name) {
        JsonNode v = node.get(name);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asInt();
    }

    static List<String> stringArray(JsonNode node, String name) {
        List<String> out = new ArrayList<String>();
        JsonNode v = node.get(name);
        if (v != null && v.isArray()) {
            Iterator<JsonNode> it = v.elements();
            while (it.hasNext()) {
                JsonNode e = it.next();
                if (e.isTextual()) {
                    out.add(e.asText());
                }
            }
        }
        return out;
    }

    /** 解析 OpenAI/Anthropic 风格的 tool_choice 到统一模型。 */
    static void applyToolChoice(JsonNode body, ChatRequest.Builder b) {
        JsonNode tc = body.get("tool_choice");
        if (tc == null || tc.isNull()) {
            return;
        }
        if (tc.isTextual()) {
            String v = tc.asText();
            if ("none".equals(v)) {
                b.toolChoice(ToolChoice.NONE);
            } else if ("required".equals(v) || "any".equals(v)) {
                b.toolChoice(ToolChoice.REQUIRED);
            } else {
                b.toolChoice(ToolChoice.AUTO);
            }
            return;
        }
        if (tc.isObject()) {
            if ("function".equals(tc.path("type").asText(""))
                    && tc.path("function").has("name")) {
                b.toolChoice(ToolChoice.FUNCTION)
                        .toolChoiceFunction(tc.path("function").path("name").asText());
            } else if ("tool".equals(tc.path("type").asText(""))
                    && tc.has("name")) {
                b.toolChoice(ToolChoice.FUNCTION).toolChoiceFunction(tc.path("name").asText());
            } else {
                b.toolChoice(ToolChoice.AUTO);
            }
        }
    }

    /** 解析 OpenAI 风格 response_format。 */
    static void applyResponseFormat(JsonNode body, ChatRequest.Builder b) {
        JsonNode rf = body.get("response_format");
        if (rf == null || !rf.isObject()) {
            return;
        }
        String type = rf.path("type").asText("");
        if ("json_object".equals(type)) {
            b.responseFormat(ResponseFormat.JSON_OBJECT);
        } else if ("json_schema".equals(type)) {
            JsonNode js = rf.get("json_schema");
            if (js != null && js.isObject()) {
                b.responseFormat(ResponseFormat.JSON_SCHEMA)
                        .responseFormatName(js.path("name").asText(null))
                        .responseFormatSchema(js.path("schema").toString());
            }
        }
    }

    /** 解析 OpenAI Responses 风格 text.format。 */
    static void applyResponsesTextFormat(JsonNode body, ChatRequest.Builder b) {
        JsonNode text = body.get("text");
        if (text == null || !text.isObject()) {
            return;
        }
        JsonNode format = text.get("format");
        if (format == null || !format.isObject()) {
            return;
        }
        String type = format.path("type").asText("");
        if ("json_object".equals(type)) {
            b.responseFormat(ResponseFormat.JSON_OBJECT);
        } else if ("json_schema".equals(type)) {
            b.responseFormat(ResponseFormat.JSON_SCHEMA)
                    .responseFormatName(format.path("name").asText(null))
                    .responseFormatSchema(format.path("schema").toString());
        }
    }
}
