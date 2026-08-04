package org.bluepowerrobotics.converter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 统一的对话响应。 */
public final class ChatResponse {
    private final String content;
    private final List<ToolCall> toolCalls;
    private final FinishReason finishReason;
    private final Usage usage;
    private final String provider;
    private final String model;
    private final String id;

    private ChatResponse(Builder b) {
        this.content = b.content;
        this.toolCalls = b.toolCalls == null
                ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<ToolCall>(b.toolCalls));
        this.finishReason = b.finishReason;
        this.usage = b.usage;
        this.provider = b.provider;
        this.model = b.model;
        this.id = b.id;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getContent() {
        return content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public FinishReason getFinishReason() {
        return finishReason;
    }

    public Usage getUsage() {
        return usage;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getId() {
        return id;
    }

    @Override
    public String toString() {
        return "ChatResponse{content='" + content + "', toolCalls=" + toolCalls + '}';
    }

    public static final class Builder {
        private String content;
        private List<ToolCall> toolCalls;
        private FinishReason finishReason;
        private Usage usage;
        private String provider;
        private String model;
        private String id;

        private Builder() {
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder toolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public Builder addToolCall(ToolCall toolCall) {
            if (toolCalls == null) {
                toolCalls = new ArrayList<ToolCall>();
            }
            toolCalls.add(toolCall);
            return this;
        }

        public Builder finishReason(FinishReason finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public ChatResponse build() {
            return new ChatResponse(this);
        }
    }
}
