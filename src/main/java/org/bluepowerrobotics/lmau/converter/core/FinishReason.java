package org.bluepowerrobotics.lmau.converter.core;

/** 统一的结束原因。 */
public enum FinishReason {
    STOP("stop"),
    LENGTH("length"),
    TOOL_CALLS("tool_calls"),
    CONTENT_FILTER("content_filter"),
    OTHER("other");

    private final String wire;

    FinishReason(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static FinishReason fromWire(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase();
        if ("stop".equals(v) || "end_turn".equals(v) || "stop_sequence".equals(v)) {
            return STOP;
        }
        if ("length".equals(v) || "max_tokens".equals(v)) {
            return LENGTH;
        }
        if ("tool_calls".equals(v) || "tool_use".equals(v) || "function_call".equals(v)) {
            return TOOL_CALLS;
        }
        if ("content_filter".equals(v) || "refusal".equals(v)) {
            return CONTENT_FILTER;
        }
        return OTHER;
    }
}
