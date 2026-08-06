package org.bluepowerrobotics.lmau.converter.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import org.bluepowerrobotics.lmau.converter.provider.ProviderConfig;
import org.bluepowerrobotics.lmau.converter.util.Json;

/** 网关配置：监听地址 + 后端提供商。 */
public final class GatewayConfig {

    /** 端点标识：chat / responses / anthropic。 */
    public static final String EP_CHAT = "chat";
    public static final String EP_RESPONSES = "responses";
    public static final String EP_ANTHROPIC = "anthropic";

    private static final Set<String> ALL_ENDPOINTS =
            Collections.unmodifiableSet(new LinkedHashSet<String>(java.util.Arrays.asList(
                    EP_CHAT, EP_RESPONSES, EP_ANTHROPIC)));

    private final String host;
    private final int port;
    private final int threads;
    private final ProviderConfig backend;
    private final Set<String> enabledEndpoints;
    private final Map<String, ProviderConfig> endpointBackends;
    private final String forceModel;

    private GatewayConfig(Builder b) {
        this.host = b.host == null ? "127.0.0.1" : b.host;
        this.port = b.port <= 0 ? 8080 : b.port;
        this.threads = b.threads <= 0 ? 8 : b.threads;
        this.backend = Objects.requireNonNull(b.backend, "backend");
        this.enabledEndpoints = Collections.unmodifiableSet(new LinkedHashSet<String>(
                b.enabledEndpoints == null || b.enabledEndpoints.isEmpty()
                        ? ALL_ENDPOINTS
                        : b.enabledEndpoints));
        this.endpointBackends = Collections.unmodifiableMap(new HashMap<String, ProviderConfig>(
                b.endpointBackends == null
                        ? Collections.<String, ProviderConfig>emptyMap()
                        : b.endpointBackends));
        this.forceModel = b.forceModel;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getThreads() {
        return threads;
    }

    public ProviderConfig getBackend() {
        return backend;
    }

    /** 启用的前端端点（chat / responses / anthropic）。 */
    public Set<String> getEnabledEndpoints() {
        return enabledEndpoints;
    }

    /** 某个端点专属的后端配置；未配置时使用全局 backend。 */
    public ProviderConfig getEndpointBackend(String endpoint) {
        return endpointBackends.get(endpoint);
    }

    public Map<String, ProviderConfig> getEndpointBackends() {
        return endpointBackends;
    }

    public boolean isEndpointEnabled(String endpoint) {
        return enabledEndpoints.contains(endpoint);
    }

    /** 强制使用指定模型（忽略客户端传入的 model），用于跨生态模型名改写。 */
    public String getForceModel() {
        return forceModel;
    }

    /** 所有启用的后端（含全局与端点专属），用于模型列表。 */
    public Set<ProviderConfig> allBackends() {
        Set<ProviderConfig> out = new LinkedHashSet<ProviderConfig>();
        out.add(backend);
        for (Map.Entry<String, ProviderConfig> e : endpointBackends.entrySet()) {
            if (enabledEndpoints.contains(e.getKey())) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    public static GatewayConfig fromJson(String json) {
        JsonNode root = Json.readTree(json);
        JsonNode backendNode = root.get("backend");
        if (backendNode == null) {
            throw new IllegalArgumentException("config requires a 'backend' object");
        }
        ProviderConfig.ProviderType type = ProviderConfig.ProviderType.fromId(
                Json.stringField(backendNode, "type"));
        if (type == null) {
            throw new IllegalArgumentException(
                    "unknown backend type: " + Json.stringField(backendNode, "type")
                            + " (expected dashscope|openai-chat|openai-responses|anthropic)");
        }
        ProviderConfig backend = ProviderConfig.builder()
                .type(type)
                .apiKey(Json.stringField(backendNode, "apiKey"))
                .baseUrl(Json.stringField(backendNode, "baseUrl"))
                .model(Json.stringField(backendNode, "model"))
                .build();

        Builder b = builder().backend(backend);
        if (root.has("host")) {
            b.host(root.get("host").asText());
        }
        if (root.has("port")) {
            b.port(root.get("port").asInt());
        }
        if (root.has("threads")) {
            b.threads(root.get("threads").asInt());
        }
        if (root.has("forceModel")) {
            b.forceModel(Json.stringField(root, "forceModel"));
        }
        if (root.has("endpoints") && root.get("endpoints").isArray()) {
            List<String> endpoints = new ArrayList<String>();
            Iterator<JsonNode> it = root.get("endpoints").elements();
            while (it.hasNext()) {
                String name = normalizeEndpoint(it.next().asText());
                if (name != null) {
                    endpoints.add(name);
                }
            }
            b.enabledEndpoints(endpoints);
        }
        if (root.has("endpointBackends") && root.get("endpointBackends").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = root.get("endpointBackends").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = normalizeEndpoint(e.getKey());
                if (name == null) {
                    continue;
                }
                JsonNode node = e.getValue();
                ProviderConfig.ProviderType epType = ProviderConfig.ProviderType.fromId(
                        Json.stringField(node, "type"));
                if (epType == null) {
                    throw new IllegalArgumentException(
                            "unknown endpoint backend type: " + Json.stringField(node, "type"));
                }
                b.endpointBackend(name, ProviderConfig.builder()
                        .type(epType)
                        .apiKey(Json.stringField(node, "apiKey"))
                        .baseUrl(Json.stringField(node, "baseUrl"))
                        .model(Json.stringField(node, "model"))
                        .build());
            }
        }
        return b.build();
    }

    /**
     * 解析单个网关配置或 {@code {"gateways": [...]}} 多网关配置，返回一个或多个 GatewayConfig。
     */
    public static List<GatewayConfig> parseAll(String json) {
        JsonNode root = Json.readTree(json);
        JsonNode gateways = root.get("gateways");
        if (gateways != null && gateways.isArray()) {
            List<GatewayConfig> list = new ArrayList<GatewayConfig>();
            for (JsonNode node : gateways) {
                list.add(fromJson(node.toString()));
            }
            return list;
        }
        return Collections.singletonList(fromJson(json));
    }

    /** 把各种别名归一化为 chat / responses / anthropic。 */
    public static String normalizeEndpoint(String name) {
        if (name == null) {
            return null;
        }
        String v = name.trim().toLowerCase();
        if ("chat".equals(v) || "chatcompletions".equals(v)
                || "chat_completions".equals(v) || "openai-chat".equals(v)) {
            return EP_CHAT;
        }
        if ("responses".equals(v) || "openai-responses".equals(v)) {
            return EP_RESPONSES;
        }
        if ("anthropic".equals(v) || "messages".equals(v) || "anthropic-messages".equals(v)) {
            return EP_ANTHROPIC;
        }
        return null;
    }

    public static GatewayConfig fromFile(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return fromJson(new String(bytes, StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "GatewayConfig{host='" + host + "', port=" + port + ", backend=" + backend + '}';
    }

    public static final class Builder {
        private String host;
        private int port;
        private int threads;
        private ProviderConfig backend;
        private Set<String> enabledEndpoints;
        private Map<String, ProviderConfig> endpointBackends;
        private String forceModel;

        private Builder() {
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        public Builder backend(ProviderConfig backend) {
            this.backend = backend;
            return this;
        }

        public Builder enabledEndpoints(List<String> enabledEndpoints) {
            this.enabledEndpoints = new HashSet<String>();
            for (String name : enabledEndpoints) {
                String n = normalizeEndpoint(name);
                if (n != null) {
                    this.enabledEndpoints.add(n);
                }
            }
            return this;
        }

        public Builder endpointBackend(String endpoint, ProviderConfig backend) {
            if (endpointBackends == null) {
                endpointBackends = new HashMap<String, ProviderConfig>();
            }
            endpointBackends.put(normalizeEndpoint(endpoint), backend);
            return this;
        }

        public Builder endpointBackends(Map<String, ProviderConfig> endpointBackends) {
            this.endpointBackends = new HashMap<String, ProviderConfig>(endpointBackends);
            return this;
        }

        public Builder forceModel(String forceModel) {
            this.forceModel = forceModel;
            return this;
        }

        public GatewayConfig build() {
            return new GatewayConfig(this);
        }
    }
}
