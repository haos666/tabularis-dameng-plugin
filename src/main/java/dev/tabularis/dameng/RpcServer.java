package dev.tabularis.dameng;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

final class RpcServer {
    private final DamengClient client;

    RpcServer(DamengClient client) {
        this.client = client;
    }

    String handleLine(String line) {
        JsonNode request;
        try {
            request = Json.MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            return serialize(error(Json.NODES.nullNode(), -32700, "parse error: " + e.getOriginalMessage()));
        }

        JsonNode id = request.has("id") ? request.get("id") : Json.NODES.nullNode();
        String method = request.path("method").asText("");
        JsonNode params = request.has("params") ? request.get("params") : Json.NODES.objectNode();

        try {
            return serialize(ok(id, dispatch(method, params)));
        } catch (RpcException e) {
            return serialize(error(id, e.code(), e.getMessage()));
        } catch (SQLException e) {
            System.err.println("Dameng JDBC error: " + e.getMessage());
            return serialize(error(id, -32603, e.getMessage()));
        } catch (Exception e) {
            System.err.println("Unexpected plugin error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return serialize(error(id, -32603, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private JsonNode dispatch(String method, JsonNode params) throws Exception {
        return switch (method) {
            case "initialize" -> {
                client.initialize(PluginSettings.fromInitializeParams(params));
                yield Json.NODES.nullNode();
            }
            case "ping" -> {
                client.ping(connectionParams(params));
                yield Json.NODES.nullNode();
            }
            case "test_connection" -> {
                client.testConnection(connectionParams(params));
                ObjectNode result = Json.NODES.objectNode();
                result.put("success", true);
                yield result;
            }
            case "get_databases" -> arrayOfStrings(client.getSchemas(connectionParams(params)));
            case "get_schemas" -> arrayOfStrings(client.getSchemas(connectionParams(params)));
            case "get_tables" -> tables(client.getTables(connectionParams(params), text(params.path("schema"))));
            case "get_columns" -> columns(client.getColumns(connectionParams(params), requiredText(params, "table"), text(params.path("schema"))));
            case "get_indexes" -> columns(client.getIndexes(connectionParams(params), requiredText(params, "table"), text(params.path("schema"))));
            case "get_foreign_keys" -> columns(client.getForeignKeys(connectionParams(params), requiredText(params, "table"), text(params.path("schema"))));
            case "get_views" -> columns(client.getViews(connectionParams(params), text(params.path("schema"))));
            case "get_view_definition" -> Json.NODES.textNode(client.getViewDefinition(
                    connectionParams(params),
                    requiredText(params, "view_name", "view", "name"),
                    text(params.path("schema"))
            ));
            case "get_view_columns" -> columns(client.getViewColumns(
                    connectionParams(params),
                    requiredText(params, "view_name", "view", "name"),
                    text(params.path("schema"))
            ));
            case "get_routines" -> columns(client.getRoutines(connectionParams(params), text(params.path("schema"))));
            case "get_routine_parameters" -> columns(client.getRoutineParameters(
                    connectionParams(params),
                    requiredText(params, "routine_name", "routine", "name"),
                    text(params.path("schema"))
            ));
            case "get_routine_definition" -> Json.NODES.textNode(client.getRoutineDefinition(
                    connectionParams(params),
                    requiredText(params, "routine_name", "routine", "name"),
                    text(params.path("routine_type")),
                    text(params.path("schema"))
            ));
            case "get_triggers" -> columns(client.getTriggers(connectionParams(params), text(params.path("schema"))));
            case "get_trigger_definition" -> Json.NODES.textNode(client.getTriggerDefinition(
                    connectionParams(params),
                    requiredText(params, "trigger_name", "trigger", "name"),
                    requiredText(params, "table_name"),
                    text(params.path("schema"))
            ));
            case "get_all_columns_batch" -> client.getAllColumnsBatch(
                    connectionParams(params),
                    text(params.path("schema")),
                    optionalStringArray(params.path("tables"))
            );
            case "get_all_foreign_keys_batch" -> client.getAllForeignKeysBatch(
                    connectionParams(params),
                    text(params.path("schema")),
                    optionalStringArray(params.path("tables"))
            );
            case "get_schema_snapshot" -> client.getSchemaSnapshot(connectionParams(params), text(params.path("schema")));
            case "execute_query" -> client.executeQuery(
                    connectionParams(params),
                    requiredText(params, "query"),
                    optionalInt(params.path("limit")),
                    intValue(params.path("page"), 1),
                    text(params.path("schema"))
            );
            case "execute_query_batch" -> client.executeQueryBatch(
                    connectionParams(params),
                    requiredStringArray(params, "queries"),
                    optionalInt(params.path("limit")),
                    intValue(params.path("page"), 1),
                    text(params.path("schema"))
            );
            case "explain_query" -> client.explainQuery(
                    connectionParams(params),
                    requiredText(params, "query"),
                    booleanValue(params.path("analyze")),
                    text(params.path("schema"))
            );
            case "insert_record" -> Json.NODES.numberNode(client.insertRecord(
                    connectionParams(params),
                    requiredText(params, "table"),
                    params.path("data"),
                    text(params.path("schema")),
                    longValue(params.path("max_blob_size"), 0)
            ));
            case "update_record" -> Json.NODES.numberNode(client.updateRecord(
                    connectionParams(params),
                    requiredText(params, "table"),
                    requiredText(params, "pk_col"),
                    params.path("pk_val"),
                    requiredText(params, "col_name"),
                    params.path("new_val"),
                    text(params.path("schema")),
                    longValue(params.path("max_blob_size"), 0)
            ));
            case "delete_record" -> Json.NODES.numberNode(client.deleteRecord(
                    connectionParams(params),
                    requiredText(params, "table"),
                    requiredText(params, "pk_col"),
                    params.path("pk_val"),
                    text(params.path("schema"))
            ));
            case "create_view" -> {
                client.createView(connectionParams(params), requiredText(params, "view_name", "view", "name"), requiredText(params, "definition"), text(params.path("schema")));
                yield Json.NODES.nullNode();
            }
            case "alter_view" -> {
                client.alterView(connectionParams(params), requiredText(params, "view_name", "view", "name"), requiredText(params, "definition"), text(params.path("schema")));
                yield Json.NODES.nullNode();
            }
            case "drop_view" -> {
                client.dropView(connectionParams(params), requiredText(params, "view_name", "view", "name"), text(params.path("schema")));
                yield Json.NODES.nullNode();
            }
            case "get_create_table_sql" -> DamengSql.toArray(DamengSql.createTableSql(
                    requiredText(params, "table_name"),
                    DamengSql.columnDefinitions(params.path("columns")),
                    text(params.path("schema"))
            ));
            case "get_add_column_sql" -> DamengSql.toArray(DamengSql.addColumnSql(
                    requiredText(params, "table"),
                    ColumnDefinition.from(params.path("column")),
                    text(params.path("schema"))
            ));
            case "get_alter_column_sql" -> DamengSql.toArray(DamengSql.alterColumnSql(
                    requiredText(params, "table"),
                    ColumnDefinition.from(params.path("old_column")),
                    ColumnDefinition.from(params.path("new_column")),
                    text(params.path("schema"))
            ));
            case "get_create_index_sql" -> DamengSql.toArray(DamengSql.createIndexSql(
                    requiredText(params, "table"),
                    requiredText(params, "index_name"),
                    DamengSql.columns(params.path("columns")),
                    booleanValue(params.path("is_unique")),
                    text(params.path("schema"))
            ));
            case "get_create_foreign_key_sql" -> DamengSql.toArray(DamengSql.createForeignKeySql(
                    requiredText(params, "table"),
                    requiredText(params, "fk_name", "constraint_name"),
                    requiredText(params, "column"),
                    requiredText(params, "ref_table"),
                    requiredText(params, "ref_column"),
                    text(params.path("on_delete")),
                    text(params.path("on_update")),
                    text(params.path("schema"))
            ));
            case "drop_index" -> {
                client.dropIndex(connectionParams(params), requiredText(params, "table"), requiredText(params, "index_name"), text(params.path("schema")));
                yield Json.NODES.nullNode();
            }
            case "drop_foreign_key" -> {
                client.dropForeignKey(connectionParams(params), requiredText(params, "table"), requiredText(params, "fk_name", "constraint_name"), text(params.path("schema")));
                yield Json.NODES.nullNode();
            }
            case "create_trigger" -> {
                client.createTrigger(connectionParams(params), requiredText(params, "trigger_sql"), text(params.path("schema")));
                yield Json.NODES.nullNode();
            }
            case "drop_trigger" -> {
                client.dropTrigger(connectionParams(params), requiredText(params, "trigger_name", "trigger", "name"), text(params.path("table_name")), text(params.path("schema")));
                yield Json.NODES.nullNode();
            }
            default -> throw new RpcException(-32601, "method '" + method + "' is not implemented");
        };
    }

    private static ConnectionParams connectionParams(JsonNode params) {
        return ConnectionParams.from(params.path("params"));
    }

    private static ObjectNode ok(JsonNode id, JsonNode result) {
        ObjectNode response = Json.NODES.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("result", result == null ? Json.NODES.nullNode() : result);
        response.set("id", id == null ? Json.NODES.nullNode() : id);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = Json.NODES.objectNode();
        ObjectNode error = Json.NODES.objectNode();
        response.put("jsonrpc", "2.0");
        error.put("code", code);
        error.put("message", message == null ? "Unknown error" : message);
        response.set("error", error);
        response.set("id", id == null ? Json.NODES.nullNode() : id);
        return response;
    }

    private static String serialize(JsonNode response) {
        try {
            return Json.MAPPER.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"serialization failed\"},\"id\":null}";
        }
    }

    private static ArrayNode arrayOfStrings(List<String> items) {
        ArrayNode array = Json.NODES.arrayNode();
        for (String item : items) {
            array.add(item);
        }
        return array;
    }

    private static ArrayNode tables(List<String> items) {
        ArrayNode array = Json.NODES.arrayNode();
        for (String item : items) {
            ObjectNode table = Json.NODES.objectNode();
            table.put("name", item);
            array.add(table);
        }
        return array;
    }

    private static ArrayNode columns(List<Map<String, JsonNode>> items) {
        ArrayNode array = Json.NODES.arrayNode();
        for (Map<String, JsonNode> item : items) {
            ObjectNode column = Json.NODES.objectNode();
            item.forEach(column::set);
            array.add(column);
        }
        return array;
    }

    private static String requiredText(JsonNode object, String field) {
        return requiredText(object, new String[]{field});
    }

    private static String requiredText(JsonNode object, String... fields) {
        String value = null;
        String primary = fields.length == 0 ? "value" : fields[0];
        for (String field : fields) {
            value = text(object.path(field));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        if (value == null || value.isBlank()) {
            throw new RpcException(-32602, "Missing required parameter '" + primary + "'.");
        }
        return value;
    }

    private static List<String> requiredStringArray(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isArray()) {
            throw new RpcException(-32602, "Missing required array parameter '" + field + "'.");
        }

        return stringArray(value);
    }

    private static List<String> optionalStringArray(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new RpcException(-32602, "Parameter 'tables' must be an array.");
        }
        return stringArray(value);
    }

    private static List<String> stringArray(JsonNode value) {
        List<String> items = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            items.add(item == null || item.isNull() ? "" : item.asText());
        }
        return items;
    }

    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer optionalInt(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asInt();
    }

    private static int intValue(JsonNode node, int fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        return node.asInt(fallback);
    }

    private static boolean booleanValue(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() && node.asBoolean(false);
    }

    private static long longValue(JsonNode node, long fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        return node.asLong(fallback);
    }
}
