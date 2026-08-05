package org.bluepowerrobotics.converter.gateway.endpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bluepowerrobotics.converter.core.ChatRequest;
import org.bluepowerrobotics.converter.util.Json;
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
}
