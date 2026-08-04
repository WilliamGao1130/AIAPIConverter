package org.bluepowerrobotics.converter.core;

import java.util.Objects;

/** 模型发起的工具调用。 */
public final class ToolCall {
    private final String id;
    private final String name;
    private final String argumentsJson;

    public ToolCall(String id, String name, String argumentsJson) {
        this.id = id;
        this.name = Objects.requireNonNull(name, "name");
        this.argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    @Override
    public String toString() {
        return "ToolCall{id='" + id + "', name='" + name + "', arguments=" + argumentsJson + '}';
    }
}
