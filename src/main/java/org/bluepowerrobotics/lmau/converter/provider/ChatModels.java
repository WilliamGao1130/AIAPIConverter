package org.bluepowerrobotics.lmau.converter.provider;

import org.bluepowerrobotics.lmau.converter.core.ChatModel;
import org.bluepowerrobotics.lmau.converter.provider.anthropic.AnthropicChatModel;
import org.bluepowerrobotics.lmau.converter.provider.dashscope.DashScopeChatModel;
import org.bluepowerrobotics.lmau.converter.provider.gemini.GeminiChatModel;
import org.bluepowerrobotics.lmau.converter.provider.openai.OpenAIChatCompletionsModel;
import org.bluepowerrobotics.lmau.converter.provider.openai.OpenAIResponsesModel;

/** 根据配置创建对应提供商适配器的工厂。 */
public final class ChatModels {

    private ChatModels() {
    }

    public static ChatModel create(ProviderConfig config) {
        switch (config.getType()) {
            case DASHSCOPE:
                return new DashScopeChatModel(config);
            case OPENAI_CHAT_COMPLETIONS:
                return new OpenAIChatCompletionsModel(config);
            case OPENAI_RESPONSES:
                return new OpenAIResponsesModel(config);
            case ANTHROPIC:
                return new AnthropicChatModel(config);
            case GEMINI:
                return new GeminiChatModel(config);
            default:
                throw new IllegalArgumentException("Unsupported provider type: " + config.getType());
        }
    }
}
