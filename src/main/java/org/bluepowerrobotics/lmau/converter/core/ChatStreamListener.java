package org.bluepowerrobotics.lmau.converter.core;

/** 流式输出的回调。 */
public interface ChatStreamListener {

    /** 收到一个内容增量。 */
    void onChunk(ChatChunk chunk);

    /** 流正常结束。 */
    void onDone();

    /** 流异常终止。 */
    void onError(Throwable error);
}
