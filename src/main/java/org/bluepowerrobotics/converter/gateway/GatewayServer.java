package org.bluepowerrobotics.converter.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.bluepowerrobotics.converter.core.ChatModel;
import org.bluepowerrobotics.converter.gateway.endpoints.AnthropicMessagesEndpoint;
import org.bluepowerrobotics.converter.gateway.endpoints.ChatCompletionsEndpoint;
import org.bluepowerrobotics.converter.gateway.endpoints.HealthEndpoint;
import org.bluepowerrobotics.converter.gateway.endpoints.ModelsEndpoint;
import org.bluepowerrobotics.converter.gateway.endpoints.ResponsesEndpoint;
import org.bluepowerrobotics.converter.provider.ChatModels;
import org.bluepowerrobotics.converter.provider.ProviderConfig;

/**
 * 本地 API 网关：把配置好的后端提供商以多种“前端”API 形态暴露出来。
 *
 * 目前支持的前端：
 * - OpenAI Chat Completions: POST /v1/chat/completions（含 SSE 流式）
 * - OpenAI Responses:        POST /v1/responses（含 SSE 流式）
 * - Anthropic Messages:      POST /v1/messages（含 SSE 流式）
 * - 辅助：GET /v1/models、GET /health
 */
public final class GatewayServer {

    private final HttpServer server;
    private final GatewayConfig config;
    private final ChatModel backend;
    private final List<ChatModel> createdBackends;

    private GatewayServer(
            HttpServer server, GatewayConfig config, ChatModel backend,
            List<ChatModel> createdBackends) {
        this.server = server;
        this.config = config;
        this.backend = backend;
        this.createdBackends = createdBackends;
    }

    /** 根据配置创建后端并启动。 */
    public static GatewayServer start(GatewayConfig config) throws IOException {
        ChatModel backend = ChatModels.create(config.getBackend());
        return start(config, backend);
    }

    /** 使用外部注入的后端启动（便于测试/嵌入）。 */
    public static GatewayServer start(GatewayConfig config, ChatModel backend) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(config.getHost(), config.getPort()), 0);
        ChatModel effective = backend != null ? backend : ChatModels.create(config.getBackend());
        List<ChatModel> created = new ArrayList<ChatModel>();
        created.add(effective);

        if (config.isEndpointEnabled(GatewayConfig.EP_CHAT)) {
            server.createContext("/v1/chat/completions", new ChatCompletionsEndpoint(
                    resolveBackend(config, GatewayConfig.EP_CHAT, effective, created),
                    defaultModelFor(config, GatewayConfig.EP_CHAT), config.getForceModel()));
        }
        if (config.isEndpointEnabled(GatewayConfig.EP_RESPONSES)) {
            server.createContext("/v1/responses", new ResponsesEndpoint(
                    resolveBackend(config, GatewayConfig.EP_RESPONSES, effective, created),
                    defaultModelFor(config, GatewayConfig.EP_RESPONSES), config.getForceModel()));
        }
        if (config.isEndpointEnabled(GatewayConfig.EP_ANTHROPIC)) {
            server.createContext("/v1/messages", new AnthropicMessagesEndpoint(
                    resolveBackend(config, GatewayConfig.EP_ANTHROPIC, effective, created),
                    defaultModelFor(config, GatewayConfig.EP_ANTHROPIC), config.getForceModel()));
        }
        server.createContext("/v1/models", new ModelsEndpoint(config));
        server.createContext("/health", new HealthEndpoint());
        server.setExecutor(Executors.newFixedThreadPool(config.getThreads()));
        server.start();
        return new GatewayServer(server, config, effective, created);
    }

    private static ChatModel resolveBackend(
            GatewayConfig config, String endpoint, ChatModel fallback,
            List<ChatModel> created) {
        ProviderConfig backendConfig = config.getEndpointBackend(endpoint);
        if (backendConfig == null) {
            return fallback;
        }
        ChatModel model = ChatModels.create(backendConfig);
        created.add(model);
        return model;
    }

    private static String defaultModelFor(GatewayConfig config, String endpoint) {
        ProviderConfig backendConfig = config.getEndpointBackend(endpoint);
        return backendConfig == null
                ? config.getBackend().getModel()
                : backendConfig.getModel();
    }

    public GatewayConfig getConfig() {
        return config;
    }

    public ChatModel getBackend() {
        return backend;
    }

    public String getAddress() {
        return "http://" + config.getHost() + ":" + config.getPort();
    }

    public void stop() {
        server.stop(0);
        for (ChatModel model : createdBackends) {
            try {
                model.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }
}
