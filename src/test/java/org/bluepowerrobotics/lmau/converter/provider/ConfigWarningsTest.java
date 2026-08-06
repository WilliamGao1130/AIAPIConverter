package org.bluepowerrobotics.lmau.converter.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigWarningsTest {

    @Test
    void placeholderKeyWarns() {
        ProviderConfig backend = ProviderConfig.builder()
                .type(ProviderConfig.ProviderType.OPENAI_CHAT_COMPLETIONS)
                .apiKey("sk-xxx-placeholder")
                .build();
        List<String> warnings = ConfigWarnings.collect(backend, null);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("占位符")));
    }

    @Test
    void wrongKeyPrefixWarns() {
        ProviderConfig backend = ProviderConfig.builder()
                .type(ProviderConfig.ProviderType.GEMINI)
                .apiKey("sk-not-a-gemini-key")
                .build();
        List<String> warnings = ConfigWarnings.collect(backend, null);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("AIza")));
    }

    @Test
    void missingKeyIsSilent() {
        ProviderConfig backend = ProviderConfig.builder()
                .type(ProviderConfig.ProviderType.ANTHROPIC)
                .baseUrl("https://api.deepseek.com/anthropic")
                .model("deepseek-chat")
                .build();
        List<String> warnings = ConfigWarnings.collect(backend, "deepseek-chat");
        assertEquals(0, warnings.size(), String.valueOf(warnings));
    }
}
