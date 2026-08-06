package org.bluepowerrobotics.lmau.converter.core;

/**
 * 统一的大模型对话接口。所有提供商适配器都实现该接口，
 * 上层代码（业务逻辑、网关）只依赖它。
 */
public interface ChatModel extends AutoCloseable {

    /** 非流式调用。 */
    ChatResponse complete(ChatRequest request);

    /**
     * 流式调用。实现必须保证 onDone/onError 恰好调用一次。
     */
    void stream(ChatRequest request, ChatStreamListener listener);

    /** 释放底层资源。 */
    @Override
    void close();
}
