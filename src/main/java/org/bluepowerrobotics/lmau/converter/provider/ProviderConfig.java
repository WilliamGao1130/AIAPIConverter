package org.bluepowerrobotics.lmau.converter.provider;

import java.util.Objects;

/** 一个后端提供商的配置。 */
public final class ProviderConfig {

    public enum ProviderType {
        DASHSCOPE("dashscope"),
        OPENAI_CHAT_COMPLETIONS("openai-chat"),
        OPENAI_RESPONSES("openai-responses"),
        ANTHROPIC("anthropic"),
        GEMINI("gemini");

        private final String id;

        ProviderType(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static ProviderType fromId(String id) {
            if (id == null) {
                return null;
            }
            String v = id.trim().toLowerCase().replace('_', '-');
            for (ProviderType t : values()) {
                if (t.id.equals(v)) {
                    return t;
                }
            }
            return null;
        }
    }

    private final ProviderType type;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    private ProviderConfig(Builder b) {
        this.type = Objects.requireNonNull(b.type, "type");
        this.apiKey = b.apiKey;
        this.baseUrl = b.baseUrl;
        this.model = b.model;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ProviderType getType() {
        return type;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    @Override
    public String toString() {
        return "ProviderConfig{type=" + type + ", model='" + model + "', baseUrl='" + baseUrl + "'}";
    }

    public static final class Builder {
        private ProviderType type;
        private String apiKey;
        private String baseUrl;
        private String model;

        private Builder() {
        }

        public Builder type(ProviderType type) {
            this.type = type;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public ProviderConfig build() {
            return new ProviderConfig(this);
        }
    }
}
