package org.bluepowerrobotics.lmau.converter.core;

/** 统一的消息角色，与各提供商 wire 格式互转。 */
public enum ChatRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wire;

    ChatRole(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 解析各提供商返回/使用的角色字符串；未知值按 user 处理。 */
    public static ChatRole fromWire(String value) {
        if (value == null) {
            return USER;
        }
        String v = value.trim().toLowerCase();
        if ("system".equals(v)) {
            return SYSTEM;
        }
        if ("assistant".equals(v) || "bot".equals(v)) {
            return ASSISTANT;
        }
        if ("tool".equals(v) || "function".equals(v)) {
            return TOOL;
        }
        return USER;
    }
}
