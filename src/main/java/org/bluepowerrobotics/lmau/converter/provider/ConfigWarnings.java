package org.bluepowerrobotics.lmau.converter.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时的智能提醒：不打扰「没配 key / 没配模型」的正常用法（客户端 key 透传场景下这是常态），
 * 只提醒用户明确提供但很可能有问题/不对的值（占位符、格式不符、DeepSeek 不认识的模型名）。
 */
public final class ConfigWarnings {

    private ConfigWarnings() {
    }

    public static void check(ProviderConfig backend, String forceModel) {
        for (String warning : collect(backend, forceModel)) {
            System.err.println("警告: " + warning);
        }
    }

    public static List<String> collect(ProviderConfig backend, String forceModel) {
        List<String> warnings = new ArrayList<String>();
        if (backend == null) {
            return warnings;
        }
        checkKey(warnings, backend.getType(), backend.getApiKey());
        checkModel(warnings, backend.getType(), backend.getBaseUrl(),
                "模型", backend.getModel());
        checkModel(warnings, backend.getType(), backend.getBaseUrl(),
                "forceModel", forceModel);
        return warnings;
    }

    private static void checkKey(
            List<String> warnings, ProviderConfig.ProviderType type, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        String lower = key.toLowerCase();
        boolean placeholder = lower.contains("xxx")
                || lower.contains("your")
                || lower.contains("dummy")
                || lower.contains("example")
                || lower.contains("fake")
                || lower.contains("test")
                || key.contains("<")
                || key.length() < 8;
        if (placeholder) {
            warnings.add("API key 看起来像占位符/测试值，可能无效: " + mask(key));
            return;
        }
        String expectedPrefix = null;
        switch (type) {
            case OPENAI_CHAT_COMPLETIONS:
            case OPENAI_RESPONSES:
                expectedPrefix = "sk-";
                break;
            case ANTHROPIC:
                expectedPrefix = "sk-ant-";
                break;
            case GEMINI:
                expectedPrefix = "AIza";
                break;
            default:
                break;
        }
        if (expectedPrefix != null && !key.startsWith(expectedPrefix)) {
            warnings.add(type.id() + " 的 API key 通常以 " + expectedPrefix
                    + " 开头，当前值可能不对: " + mask(key));
        }
    }

    private static void checkModel(
            List<String> warnings, ProviderConfig.ProviderType type,
            String baseUrl, String label, String model) {
        if (model == null || model.isEmpty()) {
            return;
        }
        String lower = model.toLowerCase();
        if (lower.contains("xxx") || lower.contains("your")
                || lower.contains("dummy") || lower.contains("example")) {
            warnings.add(label + " 看起来像占位符: " + model);
        }
    }

    private static String mask(String key) {
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
