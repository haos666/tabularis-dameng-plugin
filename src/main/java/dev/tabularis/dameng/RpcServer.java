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
            case "execute_query" -> client.executeQuery(
                    connectionParams(params),
                    requiredText(params, "query"),
                    optionalInt(params.path("limit")),
                    intValue(params.path("page"), 1)
            );
            case "get_indexes", "get_foreign_keys", "get_views", "get_view_columns",
                    "get_routines", "get_routine_parameters", "get_schema_snapshot" -> Json.NODES.arrayNode();
            case "get_all_columns_batch", "get_all_foreign_keys_batch" -> Json.NODES.objectNode();
            case "insert_record", "update_record", "delete_record",
                    "get_create_table_sql", "get_add_column_sql", "get_alter_column_sql",
                    "get_create_index_sql", "get_create_foreign_key_sql",
                    "drop_index", "drop_foreign_key", "create_view", "alter_view", "drop_view",
                    "explain_query" -> throw new RpcException(-32601, "method '" + method + "' is not implemented by the read-only Dameng plugin");
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
        String value = text(object.path(field));
        if (value == null || value.isBlank()) {
            throw new RpcException(-32602, "Missing required parameter '" + field + "'.");
        }
        return value;
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
}
