# AI API Converter

一个把不同 AI 提供商 API 统一成一套 Java 接口的转换器，并附带一个本地 HTTP 网关：
可以把某个后端提供商（DashScope / OpenAI / Anthropic / Gemini / DeepSeek）以 **OpenAI Chat
Completions、OpenAI Responses、Anthropic Messages** 三种协议形态暴露给其他软件。

例如：你的工具只认 OpenAI 格式，但你想用阿里云百炼的 Qwen，或想用 Claude —— 只需把工具的
`base_url` 指向本地网关即可，不用改代码。

## 支持的后端（Provider）

| Provider | 依赖 | 说明 |
| --- | --- | --- |
| `dashscope` | `com.alibaba:dashscope-sdk-java:2.22.28` | 阿里云百炼（Qwen 系列）官方 Java SDK |
| `openai-chat` | `com.openai:openai-java:4.50.0` | OpenAI Chat Completions API 官方 SDK |
| `openai-responses` | `com.openai:openai-java:4.50.0` | OpenAI Responses API 官方 SDK |
| `anthropic` | `com.anthropic:anthropic-java:2.52.0` | Anthropic Messages API 官方 SDK |
| `gemini` | `com.google.genai:google-genai:1.64.0` | Google Gemini（官方 Java SDK 目前用 GenerateContent，Interactions API 尚未发布到 Java） |

OpenAI 后端支持通过 `baseUrl` 指向任意 OpenAI 兼容端点（第三方中转、本地推理服务等），
因此 DeepSeek、Moonshot、智谱等 OpenAI 兼容服务都可以直接接入。
（DeepSeek 没有专属协议：OpenAI 兼容端点用 `openai-chat` + `--base-url https://api.deepseek.com`，
Anthropic 兼容端点用 `anthropic` + `--base-url https://api.deepseek.com/anthropic`。）

API key 也可以从环境变量读取：`DASHSCOPE_API_KEY`、`OPENAI_API_KEY`、`ANTHROPIC_API_KEY`、
`GEMINI_API_KEY`。

## 快速开始

### 1) 命令行一次性对话

```bash
java -jar build/libs/AIAPIConverter-1.0-SNAPSHOT-all.jar chat \
  --provider dashscope --api-key sk-xxx --model qwen-plus \
  --message "用一句话介绍你自己" --stream
```

### 2) 启动本地网关

```bash
java -jar build/libs/AIAPIConverter-1.0-SNAPSHOT-all.jar serve \
  --provider anthropic --api-key sk-ant-xxx --model claude-sonnet-4-20250514 --port 8080
```

或用配置文件：

```bash
java -jar build/libs/AIAPIConverter-1.0-SNAPSHOT-all.jar serve --config gateway.example.json
```

只开启部分端点、并为不同端点指定不同后端（比如 `/v1/chat/completions` 走 DeepSeek、
`/v1/messages` 走 Anthropic）：

```bash
java -jar build/libs/AIAPIConverter-1.0-SNAPSHOT-all.jar serve --config gateway.multi.example.json

# 或者命令行只开 chat 端点：
java -jar build/libs/AIAPIConverter-1.0-SNAPSHOT-all.jar serve \
  --provider openai-chat --api-key sk-xxx \
  --base-url https://api.deepseek.com --port 8080 --endpoint chat
```

也可以**一条命令行直接分段启动多个网关**（等效于 `gateways` 数组，但更接近多进程写法）：
用裸 `serve` 分段，每段是一套独立配置：

```bash
java -jar build/libs/AIAPIConverter-1.0-SNAPSHOT-all.jar serve \
  --provider anthropic --base-url https://api.deepseek.com/anthropic \
  --force-model deepseek-v4-flash --endpoint openai-chat --port 19726 \
serve \
  --provider openai-chat --base-url https://api.deepseek.com/ \
  --force-model deepseek-v4-flash --endpoint openai-responses --port 19725
```

这在**一个 JVM** 里以 cluster 方式同时启动，Ctrl+C 一次全停，内存只付一次 JVM 开销
（实测两个网关 ≈97MB，而两个独立 JVM 进程 ≈148MB）。

### 多个端口、多个 base URL（一个进程）

如果想在同一台机器上用不同端口连接不同后端（比如 19725 → DeepSeek、19726 → OpenAI、
19727 → 百炼），可以一次启动多个网关。配置文件中用 `gateways` 数组定义，每个元素就是一个
独立的 `GatewayConfig`（端口、端点、后端、baseUrl、forceModel 各自独立）：

```bash
java -jar build/libs/AIAPIConverter-1.0-SNAPSHOT-all.jar serve \
  --config gateway.multi-port.example.json
```

见 [gateway.multi-port.example.json](gateway.multi-port.example.json)。一个进程、一个终端，
Ctrl+C 一次性停止全部。如果更喜欢完全隔离，也可以开多个终端分别跑多个 `serve` 命令，效果相同。

启动后，任何 OpenAI 生态客户端都可以这样使用：

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen-plus","messages":[{"role":"user","content":"你好"}],"stream":true}'
```

## 网关端点

| 端点 | 协议 | 流式 |
| --- | --- | --- |
| `POST /v1/chat/completions` | OpenAI Chat Completions | ✅ SSE |
| `POST /v1/responses` | OpenAI Responses | ✅ SSE |
| `POST /v1/messages` | Anthropic Messages | ✅ SSE |
| `GET /v1/models` | 模型列表 | - |
| `GET /health` | 存活探针 | - |

网关把三种前端协议的请求统一转换为内部 `ChatRequest`，路由到配置的后端，再把后端的
`ChatResponse`/流式块转换回对应协议的响应。工具调用（function calling）在三种协议间也会被
正确转换（含多轮 tool_use / tool_result）。

### 可控的本地服务

- `host` / `port` / `threads`：监听地址、端口、线程数。
- `endpoints`：只开启指定的前端 API（`chat` / `responses` / `anthropic`），未列出的端点
  不注册（返回 404）。命令行可用 `--endpoint chat` 重复传入。
- `endpointBackends`：每个端点可指定**独立的**后端提供商与 base URL（见
  `gateway.multi.example.json`），未指定的端点使用全局 `backend`。
- `forceModel`：**强制使用指定模型名**，忽略客户端请求里的 `model` 字段。跨生态时很有用：
  比如客户端只能填 `gpt-4o`，但后端只接受另一个名字，设 `forceModel: "<后端模型名>"` 即可。
  命令行用 `--force-model <m>`。
- 所有后端的 `baseUrl` 均可配置，这是接入 DeepSeek 等 OpenAI 兼容服务的关键。

### API key 透传

网关启动时的 `--api-key` 是**默认 key**（后端请求的兜底）。如果客户端请求自带 key，网关会
**优先转发客户端 key** 去调后端：

- `Authorization: Bearer <key>`（OpenAI 生态客户端默认就是这种）
- `x-api-key: <key>`（Anthropic 生态客户端）

网关自身不校验客户端 key。这样每个客户端可以各自携带自己的额度/账号；不带 key 的请求则使用
网关启动时配置的默认 key。

### 启动提醒

网关不会因为「没配 API key」而打扰你（客户端 key 透传场景下这是正常状态）。只在你**提供了
可疑的值**时提醒：占位符/格式不符的 key（如 OpenAI 应 `sk-` 开头、Gemini 应 `AIza` 开头），
以及看起来像占位符的模型名。模型名是否真实存在由后端决定，网关不做硬编码判断。

### 高级请求参数

网关与统一 API 支持：

- **多模态**：`content` 数组中的 `text` / `image_url`（OpenAI 格式）、`input_text` /
  `input_image`（Responses 格式）、`image` 块（Anthropic 格式）都会转换后发送给后端。
  图片支持 URL 与 data URL；Gemini 后端会自动下载 http(s) 图片并转为 inline 数据。
- **`tool_choice`**：`"auto"` / `"none"` / `"required"` /
  `{"type":"function","function":{...}}` 会映射到各后端（Anthropic 用 auto/none/any/tool，
  Gemini 用 FunctionCallingConfig）。
- **`response_format`**：`json_object` 与 `json_schema` 会映射到 OpenAI / Responses /
  DashScope / Gemini；Anthropic 协议本身没有该参数，会忽略。

## 作为库嵌入其他 Java 软件

核心是 `core.org.bluepowerrobotics.lmau.converter.ChatModel` 接口，只有几个方法：

```java


ProviderConfig config = ProviderConfig.builder()
        .type(ProviderConfig.ProviderType.DASHSCOPE)
        .apiKey("sk-xxx")
        .model("qwen-plus")
        .build();

try(
ChatModel model = ChatModels.create(config)){
ChatRequest req = ChatRequest.builder()
        .addMessage(ChatMessage.user("你好"))
        .build();
ChatResponse resp = model.complete(req);   // 或 model.stream(req, listener)
    System.out.

println(resp.getContent());
        }
```

### 依赖方式

项目使用 `java-library` 插件，SDK 依赖以 `api` 暴露，消费方（Gradle/Maven）会自动传递。
如果只想用核心 + 某个提供商，可以自行排除其他 SDK 依赖。

构建产物：

```bash
./gradlew jar     # 瘦 jar（不含依赖，适合作为库发布）
./gradlew fatJar  # 可执行 fat jar（含全部依赖，适合独立运行网关）
```

## 架构

```
src/main/java/org/bluepowerrobotics/converter/
├── core/      统一模型：ChatRequest / ChatMessage / ChatResponse / ChatChunk / ChatModel
├── provider/  五个适配器 + ProviderConfig + ChatModels 工厂
├── gateway/   本地 HTTP 网关（com.sun.net.httpserver，无外部服务器依赖）
└── util/      Jackson 工具
```

`core` 与 `provider` 不依赖 `gateway`，嵌入式使用时可以只带前两者。

## 兼容性说明

- 源码以 **Java 8** 为目标编译（`sourceCompatibility = 1.8`），SDK 依赖均为 Java 8 兼容。
- `gateway` 包使用 JDK 内置的 `com.sun.net.httpserver`，在 Android 上不可用；如果之后要嵌入
  Android（如 FTC 机器人控制器），只使用 `core` + `provider` 包即可，或为网关另写一个
  Android 可用的 HTTP 服务器实现（接口已与协议转换逻辑解耦）。
- OpenAI / Anthropic 官方 SDK 是 Kotlin 实现，但生成的公开 API 完全可以从 Java 调用
  （builder + getter 风格）。
- SDK 们依赖 okhttp / jackson / guava 等，Gradle 统一解析为兼容版本；fat jar 未做 shade 重定位，
  嵌入到已有大型项目时建议使用瘦 jar 依赖方式而非 fat jar。

## 与 Interactions API 的关系

Google Interactions API 已于 2026-06 正式 GA，但**官方 Java SDK（google-genai）目前尚未发布
Interactions 接口**（Python/Go 已支持）。因此 Gemini 适配器目前使用官方 SDK 的
`generateContent` / `generateContentStream`，覆盖对话、流式、工具调用、多模态、结构化输出；
等官方 Java SDK 支持 Interactions 后，适配器内部换到 `interactions()` 即可，对外接口不变。

## 测试

```bash
./gradlew test
```

测试使用桩后端启动真实网关，覆盖三种前端协议的非流式/流式 JSON 形状、模型列表、健康检查、
端点开关、多模态与 tool_choice/response_format 透传、错误响应，不需要真实 API key。

## 主要限制

- 多模态目前支持文本 + 图片（URL / data URL）；音频、视频、文件块会忽略。
- Anthropic 协议没有 `response_format` 参数，JSON 输出可用工具或提示词实现。
- 网关未内置鉴权，默认只监听 `127.0.0.1`，如需对外暴露请自行加反向代理鉴权。
