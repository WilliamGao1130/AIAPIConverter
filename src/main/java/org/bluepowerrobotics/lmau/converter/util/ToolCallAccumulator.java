package org.bluepowerrobotics.lmau.converter.util;

import org.bluepowerrobotics.lmau.converter.core.ToolCall;

/** 流式工具调用的增量累积器（各家 SDK 的 arguments 都以片段到达）。 */
public final class ToolCallAccumulator {
    public String id;
    public String name;
    public final StringBuilder args = new StringBuilder();

    public ToolCall toToolCall() {
        return new ToolCall(id, name, args.toString());
    }
}
