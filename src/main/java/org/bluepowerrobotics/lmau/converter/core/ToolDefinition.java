package org.bluepowerrobotics.lmau.converter.core;

import java.util.Objects;

/** 提供给模型的 function 工具定义。 */
public final class ToolDefinition {
    private final String name;
    private final String description;
    private final String parametersJson;

    public ToolDefinition(String name, String description, String parametersJson) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.parametersJson = parametersJson == null ? "{\"type\":\"object\",\"properties\":{}}" : parametersJson;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    @Override
    public String toString() {
        return "ToolDefinition{name='" + name + "'}";
    }
}
