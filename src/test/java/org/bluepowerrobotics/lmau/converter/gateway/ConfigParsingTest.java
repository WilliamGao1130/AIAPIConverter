package org.bluepowerrobotics.lmau.converter.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bluepowerrobotics.lmau.converter.provider.ProviderConfig;
import org.junit.jupiter.api.Test;

class ConfigParsingTest {

    @Test
    void gatewayConfigFromJson() {
        String json = "{"
                + "\"host\":\"0.0.0.0\",\"port\":9000,"
                + "\"forceModel\":\"deepseek-v4-flash\","
                + "\"endpoints\":[\"chat\",\"anthropic\"],"
                + "\"endpointBackends\":{\"chat\":{\"type\":\"openai-chat\","
                + "\"apiKey\":\"sk-ds\",\"baseUrl\":\"https://api.deepseek.com\","
                + "\"model\":\"deepseek-v4-flash\"}},"
                + "\"backend\":{\"type\":\"anthropic\",\"apiKey\":\"sk-ant-xxx\","
                + "\"model\":\"claude-sonnet-4-20250514\",\"baseUrl\":\"https://api.anthropic.com\"}"
                + "}";
        GatewayConfig config = GatewayConfig.fromJson(json);
        assertEquals("0.0.0.0", config.getHost());
        assertEquals(9000, config.getPort());
        assertEquals(ProviderConfig.ProviderType.ANTHROPIC, config.getBackend().getType());
        assertEquals("sk-ant-xxx", config.getBackend().getApiKey());
        assertEquals("claude-sonnet-4-20250514", config.getBackend().getModel());
        assertEquals(2, config.getEnabledEndpoints().size());
        assertTrue(config.isEndpointEnabled(GatewayConfig.EP_CHAT));
        assertTrue(config.isEndpointEnabled(GatewayConfig.EP_ANTHROPIC));
        assertFalse(config.isEndpointEnabled(GatewayConfig.EP_RESPONSES));
        assertEquals(ProviderConfig.ProviderType.OPENAI_CHAT_COMPLETIONS,
                config.getEndpointBackend(GatewayConfig.EP_CHAT).getType());
        assertEquals("https://api.deepseek.com",
                config.getEndpointBackend(GatewayConfig.EP_CHAT).getBaseUrl());
        assertEquals("deepseek-v4-flash", config.getForceModel());
    }

    @Test
    void providerTypeFromId() {
        assertEquals(ProviderConfig.ProviderType.DASHSCOPE,
                ProviderConfig.ProviderType.fromId("dashscope"));
        assertEquals(ProviderConfig.ProviderType.OPENAI_CHAT_COMPLETIONS,
                ProviderConfig.ProviderType.fromId("openai-chat"));
        assertEquals(ProviderConfig.ProviderType.OPENAI_RESPONSES,
                ProviderConfig.ProviderType.fromId("openai-responses"));
        assertEquals(ProviderConfig.ProviderType.ANTHROPIC,
                ProviderConfig.ProviderType.fromId("anthropic"));
        assertEquals(ProviderConfig.ProviderType.GEMINI,
                ProviderConfig.ProviderType.fromId("gemini"));
        assertEquals(null, ProviderConfig.ProviderType.fromId("deepseek"));
    }

    @Test
    void multiGatewayParse() {
        String json = "{\"gateways\":["
                + "{\"port\":19725,\"backend\":{\"type\":\"anthropic\",\"apiKey\":\"k1\","
                + "\"baseUrl\":\"https://api.deepseek.com/anthropic\",\"model\":\"deepseek-chat\"}},"
                + "{\"port\":19726,\"backend\":{\"type\":\"openai-chat\",\"apiKey\":\"k2\","
                + "\"baseUrl\":\"https://api.openai.com/v1\",\"model\":\"gpt-4o\"}}"
                + "]}";
        List<GatewayConfig> configs = GatewayConfig.parseAll(json);
        assertEquals(2, configs.size());
        assertEquals(19725, configs.get(0).getPort());
        assertEquals(ProviderConfig.ProviderType.ANTHROPIC,
                configs.get(0).getBackend().getType());
        assertEquals("https://api.deepseek.com/anthropic",
                configs.get(0).getBackend().getBaseUrl());
        assertEquals(19726, configs.get(1).getPort());
        assertEquals("https://api.openai.com/v1", configs.get(1).getBackend().getBaseUrl());
    }
}
