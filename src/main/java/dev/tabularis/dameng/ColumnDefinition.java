package dev.tabularis.dameng;

import com.fasterxml.jackson.databind.JsonNode;

record ColumnDefinition(
        String name,
        String dataType,
        boolean nullable,
        boolean primaryKey,
        boolean autoIncrement,
        String defaultValue
) {
    static ColumnDefinition from(JsonNode node) {
        String name = text(node.path("name"));
        String dataType = text(node.path("data_type"));
        if (name == null || name.isBlank()) {
            throw new RpcException(-32602, "Column definition is missing 'name'.");
        }
        if (dataType == null || dataType.isBlank()) {
            throw new RpcException(-32602, "Column definition is missing 'data_type'.");
        }
        return new ColumnDefinition(
                name,
                dataType,
                node.path("is_nullable").asBoolean(true),
                node.path("is_pk").asBoolean(false),
                node.path("is_auto_increment").asBoolean(false),
                text(node.path("default_value"))
        );
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }
}
