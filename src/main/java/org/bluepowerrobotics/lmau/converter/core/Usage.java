package org.bluepowerrobotics.lmau.converter.core;

/** 统一的 token 用量。 */
public final class Usage {
    private final Long promptTokens;
    private final Long completionTokens;
    private final Long totalTokens;

    public Usage(Long promptTokens, Long completionTokens, Long totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    @Override
    public String toString() {
        return "Usage{prompt=" + promptTokens + ", completion=" + completionTokens + '}';
    }
}
