package org.bluepowerrobotics.lmau.converter;

import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bluepowerrobotics.lmau.converter.core.ChatChunk;
import org.bluepowerrobotics.lmau.converter.core.ChatMessage;
import org.bluepowerrobotics.lmau.converter.core.ChatModel;
import org.bluepowerrobotics.lmau.converter.core.ChatRequest;
import org.bluepowerrobotics.lmau.converter.core.ChatResponse;
import org.bluepowerrobotics.lmau.converter.core.ChatStreamListener;
import org.bluepowerrobotics.lmau.converter.gateway.GatewayConfig;
import org.bluepowerrobotics.lmau.converter.gateway.GatewayCluster;
import org.bluepowerrobotics.lmau.converter.gateway.GatewayServer;
import org.bluepowerrobotics.lmau.converter.provider.ChatModels;
import org.bluepowerrobotics.lmau.converter.provider.ConfigWarnings;
import org.bluepowerrobotics.lmau.converter.provider.ProviderConfig;

/**
 * 命令行入口：
 *
 * <pre>
 *   java -jar AIAPIConverter-all.jar --chat "你好" --provider dashscope --api-key xxx
 *   java -jar AIAPIConverter-all.jar --serve --provider anthropic --api-key xxx --model claude-sonnet-4-20250514 --port 8080
 *   java -jar AIAPIConverter-all.jar --serve --config gateway.json
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        if (a.help || a.command == null) {
            printUsage();
            return;
        }
        if ("chat".equals(a.command)) {
            runChat(a);
        } else if ("serve".equals(a.command)) {
            runServe(args);
        } else {
            System.err.println("Unknown command: " + a.command);
            printUsage();
            System.exit(2);
        }
    }

    private static void runChat(Args a) throws Exception {
        if (a.message == null) {
            System.err.println("--message is required for 'chat'");
            System.exit(2);
        }
        ProviderConfig config = buildProviderConfig(a);
        ChatRequest request = ChatRequest.builder()
                .model(config.getModel())
                .addMessage(ChatMessage.user(a.message))
                .stream(a.stream)
                .build();
        try (ChatModel model = ChatModels.create(config)) {
            if (a.stream) {
                model.stream(request, new ChatStreamListener() {
                    @Override
                    public void onChunk(ChatChunk chunk) {
                        if (chunk.getContent() != null) {
                            System.out.print(chunk.getContent());
                            System.out.flush();
                        }
                    }

                    @Override
                    public void onDone() {
                        System.out.println();
                    }

                    @Override
                    public void onError(Throwable error) {
                        System.err.println("\nError: " + error);
                    }
                });
            } else {
                ChatResponse r = model.complete(request);
                System.out.println(r.getContent());
            }
        }
    }

    private static void runServe(String[] args) throws Exception {
        List<Args> blocks = Args.parseServeBlocks(args);
        if (blocks.size() > 1) {
            List<GatewayConfig> configs = new ArrayList<GatewayConfig>();
            for (Args block : blocks) {
                if (block.config != null) {
                    System.err.println(
                            "serve 分段模式不支持 --config；多网关请用 gateways 数组配置文件。");
                    System.exit(2);
                }
                GatewayConfig config = buildCliGatewayConfig(block);
                warnConfig(config);
                configs.add(config);
            }
            runServeCluster(configs);
            return;
        }
        Args a = blocks.isEmpty() ? new Args() : blocks.get(0);
        if (a.config != null) {
            byte[] bytes = Files.readAllBytes(Paths.get(a.config));
            java.util.List<GatewayConfig> configs =
                    GatewayConfig.parseAll(new String(bytes, StandardCharsets.UTF_8));
            if (configs.size() > 1) {
                for (GatewayConfig c : configs) {
                    warnConfig(c);
                }
                runServeCluster(configs);
                return;
            }
            GatewayConfig config = applyCliOverrides(configs.get(0), a);
            warnConfig(config);
            runServeSingle(config);
            return;
        }
        GatewayConfig config = buildCliGatewayConfig(a);
        warnConfig(config);
        runServeSingle(config);
    }

    private static void warnConfig(GatewayConfig config) {
        ConfigWarnings.check(config.getBackend(), config.getForceModel());
        for (ProviderConfig endpointBackend : config.getEndpointBackends().values()) {
            ConfigWarnings.check(endpointBackend, config.getForceModel());
        }
    }

    private static GatewayConfig buildCliGatewayConfig(Args a) {
        return GatewayConfig.builder()
                .host(a.host == null ? "127.0.0.1" : a.host)
                .port(a.port > 0 ? a.port : 8080)
                .backend(buildProviderConfig(a))
                .enabledEndpoints(a.endpoints.isEmpty() ? null : a.endpoints)
                .forceModel(a.forceModel)
                .build();
    }

    /** CLI 覆盖（host/port/endpoints/forceModel）叠加到配置之上，保留其他字段。 */
    private static GatewayConfig applyCliOverrides(GatewayConfig config, Args a) {
        // CLI 覆盖（host/port/endpoints/forceModel）叠加到配置文件之上，保留其他字段
        boolean override = a.host != null || a.port > 0 || !a.endpoints.isEmpty()
                || a.forceModel != null;
        if (override) {
            GatewayConfig.Builder b = GatewayConfig.builder()
                    .host(a.host != null ? a.host : config.getHost())
                    .port(a.port > 0 ? a.port : config.getPort())
                    .threads(config.getThreads())
                    .backend(config.getBackend())
                    .endpointBackends(config.getEndpointBackends())
                    .forceModel(a.forceModel != null ? a.forceModel : config.getForceModel());
            if (a.endpoints.isEmpty()) {
                b.enabledEndpoints(new ArrayList<String>(config.getEnabledEndpoints()));
            } else {
                b.enabledEndpoints(a.endpoints);
            }
            config = b.build();
        }
        return config;
    }

    private static void runServeCluster(java.util.List<GatewayConfig> configs) throws Exception {
        GatewayCluster cluster = GatewayCluster.start(configs);
        System.out.println("AI API Converter: " + configs.size() + " 个网关已启动");
        int i = 1;
        for (GatewayServer server : cluster.getServers()) {
            GatewayConfig config = server.getConfig();
            String effectiveModel = config.getForceModel() != null
                    ? config.getForceModel() + " (force)"
                    : config.getBackend().getModel();
            System.out.println("  [" + (i++) + "] " + server.getAddress()
                    + "  backend=" + config.getBackend().getType().id()
                    + "  model=" + effectiveModel);
        }
        System.out.println("Press Ctrl+C to stop all.");
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                cluster.stop();
            }
        }));
        Thread.currentThread().join();
    }

    private static void runServeSingle(GatewayConfig config) throws Exception {
        GatewayServer server = GatewayServer.start(config);
        System.out.println("AI API Converter gateway started at " + server.getAddress());
        if (config.isEndpointEnabled(GatewayConfig.EP_CHAT)) {
            System.out.println("  POST " + server.getAddress() + "/v1/chat/completions  (OpenAI Chat Completions)");
        }
        if (config.isEndpointEnabled(GatewayConfig.EP_RESPONSES)) {
            System.out.println("  POST " + server.getAddress() + "/v1/responses        (OpenAI Responses)");
        }
        if (config.isEndpointEnabled(GatewayConfig.EP_ANTHROPIC)) {
            System.out.println("  POST " + server.getAddress() + "/v1/messages         (Anthropic Messages)");
        }
        System.out.println("  GET  " + server.getAddress() + "/v1/models");
        System.out.println("Press Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                server.stop();
            }
        }));
        Thread.currentThread().join();
    }

    private static ProviderConfig buildProviderConfig(Args a) {
        ProviderConfig.ProviderType type = ProviderConfig.ProviderType.fromId(a.provider);
        if (type == null) {
            System.err.println("--provider must be one of: dashscope, openai-chat, openai-responses, anthropic, gemini");
            System.exit(2);
        }
        String apiKey = a.apiKey;
        if (apiKey == null) {
            if (type == ProviderConfig.ProviderType.DASHSCOPE) {
                apiKey = System.getenv("DASHSCOPE_API_KEY");
            } else if (type == ProviderConfig.ProviderType.OPENAI_CHAT_COMPLETIONS
                    || type == ProviderConfig.ProviderType.OPENAI_RESPONSES) {
                apiKey = System.getenv("OPENAI_API_KEY");
            } else if (type == ProviderConfig.ProviderType.ANTHROPIC) {
                apiKey = System.getenv("ANTHROPIC_API_KEY");
            } else if (type == ProviderConfig.ProviderType.GEMINI) {
                apiKey = System.getenv("GEMINI_API_KEY");
            }
        }
        return ProviderConfig.builder()
                .type(type)
                .apiKey(apiKey)
                .baseUrl(a.baseUrl)
                .model(a.model)
                .build();
    }

    private static void printUsage() {
        System.out.println("AI API Converter - unify DashScope / OpenAI / Anthropic APIs");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar AIAPIConverter.jar chat   --provider <p> --api-key <k> --message <text> [--model <m>] [--stream]");
        System.out.println("  java -jar AIAPIConverter.jar serve  --provider <p> --api-key <k> [--model <m>] [--port <port>] [--host <host>] [--endpoint chat|responses|anthropic] [--force-model <m>]");
        System.out.println("  java -jar AIAPIConverter.jar serve  --config <gateway.json>");
        System.out.println("  # 一条命令启动多个网关：用裸 serve 分段，每个 serve 段是一套独立配置");
        System.out.println("  java -jar AIAPIConverter.jar serve --provider anthropic --base-url <urlA> --port 19726 --endpoint openai-chat serve --provider openai-chat --base-url <urlB> --port 19725 --endpoint openai-responses");
        System.out.println("  # 多端口多后端：配置文件中写 {\"gateways\":[{...},{...}]} 即可一次启动多个");
        System.out.println();
        System.out.println("Providers: dashscope | openai-chat | openai-responses | anthropic | gemini");
        System.out.println("--endpoint 可重复传入，只开启指定的前端 API；不传则全部开启。");
        System.out.println("--force-model 强制使用指定模型名（忽略客户端传入的 model），用于跨生态改写。");
        System.out.println("API key 可选：不传时后端需要鉴权会返回 401，本地无鉴权服务可省略。");
        System.out.println("API keys can also come from DASHSCOPE_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY / GEMINI_API_KEY.");
    }

    static final class Args {
        private static final Set<String> VALUE_OPTIONS = new HashSet<String>(Arrays.asList(
                "--provider", "--api-key", "--base-url", "--model", "--force-model",
                "--message", "--config", "--host", "--port", "--endpoint"));

        String command;
        String provider;
        String apiKey;
        String baseUrl;
        String model;
        String message;
        String config;
        String host;
        String forceModel;
        List<String> endpoints = new ArrayList<String>();
        int port;
        boolean stream;
        boolean help;

        static Args parse(String[] args) {
            Args a = new Args();
            List<String> rest = new java.util.ArrayList<String>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    a.help = true;
                } else if ("--provider".equals(arg) && i + 1 < args.length) {
                    a.provider = args[++i];
                } else if ("--api-key".equals(arg) && i + 1 < args.length) {
                    a.apiKey = args[++i];
                } else if ("--base-url".equals(arg) && i + 1 < args.length) {
                    a.baseUrl = args[++i];
                } else if ("--model".equals(arg) && i + 1 < args.length) {
                    a.model = args[++i];
                } else if ("--message".equals(arg) && i + 1 < args.length) {
                    a.message = args[++i];
                } else if ("--config".equals(arg) && i + 1 < args.length) {
                    a.config = args[++i];
                } else if ("--host".equals(arg) && i + 1 < args.length) {
                    a.host = args[++i];
                } else if ("--endpoint".equals(arg) && i + 1 < args.length) {
                    a.endpoints.add(args[++i]);
                } else if ("--force-model".equals(arg) && i + 1 < args.length) {
                    a.forceModel = args[++i];
                } else if ("--port".equals(arg) && i + 1 < args.length) {
                    a.port = Integer.parseInt(args[++i]);
                } else if ("--stream".equals(arg)) {
                    a.stream = true;
                } else if (arg.startsWith("-")) {
                    System.err.println("Unknown option: " + arg);
                } else {
                    rest.add(arg);
                }
            }
            if (!rest.isEmpty()) {
                a.command = rest.get(0);
            }
            return a;
        }

        /**
         * 支持在一行命令里用多个裸 serve 分段，每段解析为一套独立的网关参数。
         * 例如：serve --port 19726 ... serve --port 19725 ...
         */
        static List<Args> parseServeBlocks(String[] args) {
            List<Args> blocks = new ArrayList<Args>();
            List<String> current = new ArrayList<String>();
            boolean expectValue = false;
            for (String arg : args) {
                if (expectValue) {
                    current.add(arg);
                    expectValue = false;
                    continue;
                }
                if ("serve".equals(arg)) {
                    if (!current.isEmpty() || !blocks.isEmpty()) {
                        blocks.add(parse(current.toArray(new String[0])));
                        current = new ArrayList<String>();
                    }
                    continue;
                }
                current.add(arg);
                if (VALUE_OPTIONS.contains(arg)) {
                    expectValue = true;
                }
            }
            if (!current.isEmpty()) {
                blocks.add(parse(current.toArray(new String[0])));
            }
            return blocks;
        }
    }
}
