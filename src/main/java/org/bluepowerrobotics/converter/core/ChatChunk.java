package org.bluepowerrobotics.converter.core;

/** 流式响应中的一个增量块。 */
public final class ChatChunk {
    private final String content;
    private final FinishReason finishReason;

    public ChatChunk(String content, FinishReason finishReason) {
        this.content = content;
        this.finishReason = finishReason;
    }

    public String getContent() {
        return content;
    }

    public FinishReason getFinishReason() {
        return finishReason;
    }

    @Override
    public String toString() {
        return "ChatChunk{content='" + content + "', finishReason=" + finishReason + '}';
    }
}
