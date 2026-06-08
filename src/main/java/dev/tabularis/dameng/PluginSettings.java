package dev.tabularis.dameng;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;

final class PluginSettings {
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_QUERY_TIMEOUT_SEC = 60;

    private final Path jdbcDriverPath;
    private final int connectTimeoutMs;
    private final int queryTimeoutSec;

    PluginSettings(Path jdbcDriverPath, int connectTimeoutMs, int queryTimeoutSec) {
        this.jdbcDriverPath = jdbcDriverPath;
        this.connectTimeoutMs = connectTimeoutMs;
        this.queryTimeoutSec = queryTimeoutSec;
    }

    static PluginSettings fromInitializeParams(JsonNode params) {
        JsonNode settings = params.path("settings");
        String rawPath = text(settings.path("jdbc_driver_path"));
        if (rawPath == null || rawPath.isBlank()) {
            throw new RpcException(-32602, "Plugin setting 'jdbc_driver_path' is required.");
        }
        return new PluginSettings(
                Path.of(rawPath),
                positiveInt(settings.path("connect_timeout_ms"), DEFAULT_CONNECT_TIMEOUT_MS),
                positiveInt(settings.path("query_timeout_sec"), DEFAULT_QUERY_TIMEOUT_SEC)
        );
    }

    Path jdbcDriverPath() {
        return jdbcDriverPath;
    }

    int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    int queryTimeoutSec() {
        return queryTimeoutSec;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static int positiveInt(JsonNode node, int fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        int value;
        if (node.isNumber()) {
            value = node.asInt(fallback);
        } else {
            try {
                value = Integer.parseInt(node.asText());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return value > 0 ? value : fallback;
    }
}
