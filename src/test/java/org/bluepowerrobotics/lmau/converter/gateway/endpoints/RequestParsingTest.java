package org.bluepowerrobotics.lmau.converter.gateway.endpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.bluepowerrobotics.lmau.converter.core.ChatMessage;
import org.bluepowerrobotics.lmau.converter.core.ChatRequest;
import org.bluepowerrobotics.lmau.converter.core.ChatRole;
import org.bluepowerrobotics.lmau.converter.core.ToolCall;
import org.bluepowerrobotics.lmau.converter.util.Json;
import org.junit.jupiter.api.Test;

class RequestParsingTest {

    private static ChatRequest.ReasoningEffort effort(String json) {
        ChatRequest.Builder b = ChatRequest.builder();
        RequestParsing.applyReasoning(Json.readTree(json), b);
        return b.build().getReasoningEffort();
    }

    @Test
    void openAiChatStyle() {
        assertEquals(ChatRequest.ReasoningEffort.NONE,
                effort("{\"reasoning_effort\":\"none\"}"));
        assertEquals(ChatRequest.ReasoningEffort.LOW,
                effort("{\"reasoning_effort\":\"low\"}"));
        assertEquals(ChatRequest.ReasoningEffort.MEDIUM,
                effort("{\"reasoning_effort\":\"medium\"}"));
        assertEquals(ChatRequest.ReasoningEffort.HIGH,
                effort("{\"reasoning_effort\":\"high\"}"));
        assertEquals(ChatRequest.ReasoningEffort.XHIGH,
                effort("{\"reasoning_effort\":\"xhigh\"}"));
        assertEquals(ChatRequest.ReasoningEffort.XHIGH,
                effort("{\"reasoning_effort\":\"max\"}"));
    }

    @Test
    void responsesAndAnthropicStyles() {
        assertEquals(ChatRequest.ReasoningEffort.NONE,
                effort("{\"reasoning\":{\"effort\":\"none\"}}"));
        assertEquals(ChatRequest.ReasoningEffort.HIGH,
                effort("{\"reasoning\":{\"effort\":\"high\"}}"));
        assertEquals(ChatRequest.ReasoningEffort.XHIGH,
                effort("{\"reasoning\":{\"effort\":\"max\"}}"));
        assertEquals(ChatRequest.ReasoningEffort.NONE,
                effort("{\"thinking\":{\"type\":\"disabled\"}}"));
        assertEquals(null, effort("{\"thinking\":{\"type\":\"enabled\"}}"));
    }

    @Test
    void absentFieldsLeaveNull() {
        assertEquals(null, effort("{\"model\":\"m\"}"));
        assertEquals(null, effort("{\"reasoning_effort\":\"weird-value\"}"));
    }

    private static ChatMessage assistantWithCalls(String content, String... ids) {
        ChatMessage.Builder b = ChatMessage.builder()
                .role(ChatRole.ASSISTANT)
                .content(content);
        for (String id : ids) {
            b.addToolCall(new ToolCall(id, "get_time", "{}"));
        }
        return b.build();
    }

    @Test
    void completeToolRoundIsUnchanged() {
        List<ChatMessage> in = Arrays.asList(
                ChatMessage.user("hi"),
                assistantWithCalls("让我查一下", "call-1"),
                ChatMessage.tool("call-1", "2026-08-11"),
                ChatMessage.user("谢谢"));
        List<ChatMessage> out = RequestParsing.sanitizeDanglingToolCalls(in);
        assertEquals(4, out.size());
        assertEquals(1, out.get(1).getToolCalls().size(),
                "完整工具轮不应被改写");
        assertEquals(ChatRole.TOOL, out.get(2).getRole());
    }

    @Test
    void danglingToolCallAtEndIsSanitized() {
        List<ChatMessage> in = Arrays.asList(
                ChatMessage.user("hi"),
                assistantWithCalls(null, "call-1"));
        List<ChatMessage> out = RequestParsing.sanitizeDanglingToolCalls(in);
        assertEquals(2, out.size());
        ChatMessage assistant = out.get(1);
        assertTrue(assistant.getContent() != null
                        && assistant.getContent().contains("get_time"),
                "悬空 tool call 应转为文本占位保留上下文");
        assertTrue(assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty(),
                "悬空 tool call 应从消息中移除");
    }

    @Test
    void danglingToolCallBeforeUserMessageIsSanitized() {
        List<ChatMessage> in = Arrays.asList(
                ChatMessage.user("hi"),
                assistantWithCalls("回答一部分", "call-1"),
                ChatMessage.user("继续"));
        List<ChatMessage> out = RequestParsing.sanitizeDanglingToolCalls(in);
        ChatMessage assistant = out.get(1);
        assertTrue(assistant.getContent().contains("回答一部分"),
                "原有正文应保留");
        assertTrue(assistant.getContent().contains("工具调用"),
                "悬空 tool call 应追加为文本");
        assertFalse(assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty(),
                "不应再携带 tool_calls");
    }

    @Test
    void orphanToolMessageBecomesText() {
        List<ChatMessage> in = Arrays.asList(
                ChatMessage.user("hi"),
                ChatMessage.tool("ghost-call", "结果"));
        List<ChatMessage> out = RequestParsing.sanitizeDanglingToolCalls(in);
        assertEquals(ChatRole.USER, out.get(1).getRole(),
                "孤立的 tool 消息应转为 user 文本");
        assertTrue(out.get(1).getContent().contains("结果"));
    }

    @Test
    void assistantReasoningContentIsParsed() {
        List<ChatMessage> msgs = RequestParsing.openAIMessages(Json.readTree(
                "[{\"role\":\"assistant\",\"content\":\"ok\","
                + "\"reasoning_content\":\"think hard\"}]"));
        assertEquals("think hard", msgs.get(0).getReasoning());
        assertEquals("ok", msgs.get(0).getContent());
    }

    @Test
    void responsesReasoningBlockIsParsed() {
        assertEquals("think hard", RequestParsing.openAIReasoning(Json.readTree(
                "{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"reasoning\",\"text\":\"think hard\"},"
                + "{\"type\":\"output_text\",\"text\":\"ok\"}]}")));
    }

    @Test
    void danglingToolCallKeepsReasoning() {
        List<ChatMessage> in = Arrays.asList(
                ChatMessage.user("hi"),
                ChatMessage.builder()
                        .role(ChatRole.ASSISTANT)
                        .content("部分回答")
                        .reasoning("内心思考")
                        .addToolCall(new ToolCall("c1", "f", "{}"))
                        .build());
        List<ChatMessage> out = RequestParsing.sanitizeDanglingToolCalls(in);
        assertEquals("内心思考", out.get(1).getReasoning(),
                "清理悬空 tool call 时应保留思考内容");
        assertTrue(out.get(1).getContent().contains("工具调用"));
    }
}
