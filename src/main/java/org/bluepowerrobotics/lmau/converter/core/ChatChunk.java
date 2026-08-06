package org.bluepowerrobotics.lmau.converter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 流式响应中的一个增量块。 */
public final class ChatChunk {
    private final String content;
    private final String reasoning;
    private final List<ToolCall> toolCalls;
    private final FinishReason finishReason;

    public ChatChunk(String content, FinishReason finishReason) {
        this(content, null, null, finishReason);
    }

    public ChatChunk(String content, String reasoning, FinishReason finishReason) {
        this(content, reasoning, null, finishReason);
    }

    public ChatChunk(String content, String reasoning,
                     List<ToolCall> toolCalls, FinishReason finishReason) {
        this.content = content;
        this.reasoning = reasoning;
        this.toolCalls = toolCalls == null
                ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
        this.finishReason = finishReason;
    }

    public String getContent() {
        return content;
    }

    /** 思考内容增量（DeepSeek reasoning_content、Claude thinking、Gemini thought 等）；无思考时为 null。 */
    public String getReasoning() {
        return reasoning;
    }

    /**
     * 本轮（一次流式响应）结束时模型发起的完整工具调用。
     * 流式过程中各家 SDK 以增量片段到达，适配器负责拼接；
     * 无工具调用时为空列表。
     */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public FinishReason getFinishReason() {
        return finishReason;
    }

    @Override
    public String toString() {
        return "ChatChunk{content='" + content + "', reasoning='" + reasoning
                + "', toolCalls=" + toolCalls + ", finishReason=" + finishReason + '}';
    }
}
