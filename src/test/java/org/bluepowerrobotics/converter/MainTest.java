package org.bluepowerrobotics.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void parseServeBlocksSplitsAtServeTokens() {
        List<Main.Args> blocks = Main.Args.parseServeBlocks(new String[]{
                "serve",
                "--provider", "anthropic",
                "--base-url", "https://api.deepseek.com/anthropic",
                "--port", "19726",
                "--endpoint", "openai-chat",
                "serve",
                "--provider", "openai-chat",
                "--base-url", "https://api.deepseek.com/",
                "--port", "19725",
                "--endpoint", "openai-responses"});

        assertEquals(2, blocks.size());
        assertEquals("anthropic", blocks.get(0).provider);
        assertEquals("https://api.deepseek.com/anthropic", blocks.get(0).baseUrl);
        assertEquals(19726, blocks.get(0).port);
        assertEquals(1, blocks.get(0).endpoints.size());
        assertEquals("openai-chat", blocks.get(0).endpoints.get(0));

        assertEquals("openai-chat", blocks.get(1).provider);
        assertEquals("https://api.deepseek.com/", blocks.get(1).baseUrl);
        assertEquals(19725, blocks.get(1).port);
        assertEquals("openai-responses", blocks.get(1).endpoints.get(0));
    }

    @Test
    void parseServeBlocksIgnoresServeAsOptionValue() {
        List<Main.Args> blocks = Main.Args.parseServeBlocks(new String[]{
                "serve",
                "--provider", "anthropic",
                "--model", "serve-model",
                "serve",
                "--provider", "openai-chat"});

        assertEquals(2, blocks.size());
        assertEquals("serve-model", blocks.get(0).model);
        assertEquals("openai-chat", blocks.get(1).provider);
    }
}
