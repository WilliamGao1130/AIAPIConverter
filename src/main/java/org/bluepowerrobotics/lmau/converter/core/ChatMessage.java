package org.bluepowerrobotics.lmau.converter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 统一的消息结构。 */
public final class ChatMessage {
    private final ChatRole role;
    private final String content;
    private final String reasoning;
    private final String name;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;
    private final List<ContentPart> contentParts;

    private ChatMessage(Builder b) {
        this.role = b.role == null ? ChatRole.USER : b.role;
        this.content = b.content;
        this.reasoning = b.reasoning;
        this.name = b.name;
        this.toolCallId = b.toolCallId;
        this.toolCalls = b.toolCalls == null
                ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<ToolCall>(b.toolCalls));
        this.contentParts = b.contentParts == null
                ? Collections.<ContentPart>emptyList()
                : Collections.unmodifiableList(new ArrayList<ContentPart>(b.contentParts));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ChatMessage system(String content) {
        return builder().role(ChatRole.SYSTEM).content(content).build();
    }

    public static ChatMessage user(String content) {
        return builder().role(ChatRole.USER).content(content).build();
    }

    public static ChatMessage assistant(String content) {
        return builder().role(ChatRole.ASSISTANT).content(content).build();
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return builder().role(ChatRole.TOOL).toolCallId(toolCallId).content(content).build();
    }

    public ChatRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    /** 思考内容（DeepSeek 等要求 tool call 回合回传 reasoning_content）。 */
    public String getReasoning() {
        return reasoning;
    }

    public String getName() {
        return name;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /** 多模态内容部件；为空时使用 getContent() 文本。 */
    public List<ContentPart> getContentParts() {
        return contentParts;
    }

    public boolean hasContentParts() {
        return !contentParts.isEmpty();
    }

    @Override
    public String toString() {
        return "ChatMessage{role=" + role.wire()
                + ", content='" + content + '\''
                + ", toolCallId='" + toolCallId + '\''
                + ", toolCalls=" + toolCalls + '}';
    }

    public static final class Builder {
        private ChatRole role;
        private String content;
        private String reasoning;
        private String name;
        private String toolCallId;
        private List<ToolCall> toolCalls;
        private List<ContentPart> contentParts;

        private Builder() {
        }

        public Builder role(ChatRole role) {
            this.role = role;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder reasoning(String reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder toolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
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

        public Builder contentParts(List<ContentPart> contentParts) {
            this.contentParts = contentParts;
            return this;
        }

        public Builder addContentPart(ContentPart part) {
            if (contentParts == null) {
                contentParts = new ArrayList<ContentPart>();
            }
            contentParts.add(part);
            return this;
        }

        public ChatMessage build() {
            return new ChatMessage(this);
        }
    }
}
