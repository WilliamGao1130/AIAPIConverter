package org.bluepowerrobotics.lmau.converter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 统一的对话请求。 */
public final class ChatRequest {
    /** 深度思考强度；NONE 表示关闭（模型自行决定或按默认）。 */
    public enum ReasoningEffort {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
        XHIGH
    }

    private final String model;
    private final List<ChatMessage> messages;
    private final List<ToolDefinition> tools;
    private final Double temperature;
    private final Integer maxTokens;
    private final Double topP;
    private final List<String> stop;
    private final boolean stream;
    private final Integer seed;
    private final ToolChoice toolChoice;
    private final String toolChoiceFunction;
    private final ResponseFormat responseFormat;
    private final String responseFormatSchema;
    private final String responseFormatName;
    private final ReasoningEffort reasoningEffort;
    private final String apiKey;

    private ChatRequest(Builder b) {
        this.model = b.model;
        this.messages = b.messages == null
                ? Collections.<ChatMessage>emptyList()
                : Collections.unmodifiableList(new ArrayList<ChatMessage>(b.messages));
        this.tools = b.tools == null
                ? Collections.<ToolDefinition>emptyList()
                : Collections.unmodifiableList(new ArrayList<ToolDefinition>(b.tools));
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.topP = b.topP;
        this.stop = b.stop == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(b.stop));
        this.stream = b.stream;
        this.seed = b.seed;
        this.toolChoice = b.toolChoice;
        this.toolChoiceFunction = b.toolChoiceFunction;
        this.responseFormat = b.responseFormat;
        this.responseFormatSchema = b.responseFormatSchema;
        this.responseFormatName = b.responseFormatName;
        this.reasoningEffort = b.reasoningEffort;
        this.apiKey = b.apiKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return model;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public List<ToolDefinition> getTools() {
        return tools;
    }

    public Double getTemperature() {
        return temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public Double getTopP() {
        return topP;
    }

    public List<String> getStop() {
        return stop;
    }

    public boolean isStream() {
        return stream;
    }

    public Integer getSeed() {
        return seed;
    }

    public ToolChoice getToolChoice() {
        return toolChoice;
    }

    public String getToolChoiceFunction() {
        return toolChoiceFunction;
    }

    public ResponseFormat getResponseFormat() {
        return responseFormat;
    }

    public String getResponseFormatSchema() {
        return responseFormatSchema;
    }

    public String getResponseFormatName() {
        return responseFormatName;
    }

    /** 深度思考强度；null/NONE 表示不设置。 */
    public ReasoningEffort getReasoningEffort() {
        return reasoningEffort;
    }

    /** 请求级 API key（来自客户端请求头），优先于网关配置的 key。 */
    public String getApiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "ChatRequest{model='" + model + "', messages=" + messages.size() + '}';
    }

    public static final class Builder {
        private String model;
        private List<ChatMessage> messages;
        private List<ToolDefinition> tools;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private List<String> stop;
        private boolean stream;
        private Integer seed;
        private ToolChoice toolChoice;
        private String toolChoiceFunction;
        private ResponseFormat responseFormat;
        private String responseFormatSchema;
        private String responseFormatName;
        private ReasoningEffort reasoningEffort;
        private String apiKey;

        private Builder() {
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<ChatMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder addMessage(ChatMessage message) {
            if (messages == null) {
                messages = new ArrayList<ChatMessage>();
            }
            messages.add(message);
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {
            this.tools = tools;
            return this;
        }

        public Builder addTool(ToolDefinition tool) {
            if (tools == null) {
                tools = new ArrayList<ToolDefinition>();
            }
            tools.add(tool);
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder stop(List<String> stop) {
            this.stop = stop;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder seed(Integer seed) {
            this.seed = seed;
            return this;
        }

        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        public Builder toolChoiceFunction(String toolChoiceFunction) {
            this.toolChoiceFunction = toolChoiceFunction;
            return this;
        }

        public Builder responseFormat(ResponseFormat responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public Builder responseFormatSchema(String responseFormatSchema) {
            this.responseFormatSchema = responseFormatSchema;
            return this;
        }

        public Builder responseFormatName(String responseFormatName) {
            this.responseFormatName = responseFormatName;
            return this;
        }

        public Builder reasoningEffort(ReasoningEffort reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(this);
        }
    }
}
