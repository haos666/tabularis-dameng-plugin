package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RpcServerTest {
    @Test
    void returnsParseErrorForInvalidJson() throws Exception {
        RpcServer server = new RpcServer(new DamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("{"));

        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals(-32700, response.path("error").path("code").asInt());
    }

    @Test
    void schemaSnapshotDispatchesToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_schema_snapshot","params":{"params":{},"schema":"DEV2"},"id":7}
                """));

        assertEquals(7, response.path("id").asInt());
        assertTrue(response.path("result").isArray());
        assertEquals("CUSTOMERS", response.path("result").path(0).path("name").asText());
    }

    @Test
    void batchMetadataDispatchesToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_all_foreign_keys_batch","params":{"params":{},"schema":"DEV2"},"id":8}
                """));

        assertEquals(8, response.path("id").asInt());
        assertTrue(response.path("result").isObject());
        assertEquals("FK_ORDERS_CUSTOMER", response.path("result").path("ORDERS").path(0).path("name").asText());
    }

    @Test
    void viewDefinitionUsesViewNameParameter() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_view_definition","params":{"params":{},"schema":"DEV2","view_name":"\\"V_ORDER_SUMMARY\\""},"id":10}
                """));

        assertEquals(10, response.path("id").asInt());
        assertEquals("SELECT * FROM ORDERS", response.path("result").asText());
    }

    @Test
    void indexMetadataDispatchesToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_indexes","params":{"params":{},"schema":"DEV2","table":"ORDERS"},"id":11}
                """));

        assertEquals(11, response.path("id").asInt());
        assertEquals("IDX_ORDERS_CUSTOMER", response.path("result").path(0).path("name").asText());
        assertEquals("CUSTOMER_ID", response.path("result").path(0).path("column_name").asText());
    }

    @Test
    void routineMetadataDispatchesToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_routines","params":{"params":{},"schema":"DEV2"},"id":12}
                """));

        assertEquals(12, response.path("id").asInt());
        assertEquals("FN_CUSTOMER_ORDER_COUNT", response.path("result").path(0).path("name").asText());
        assertEquals("FUNCTION", response.path("result").path(0).path("routine_type").asText());
        assertTrue(response.path("result").path(0).path("definition").asText().contains("RETURN INT"));
    }

    @Test
    void routineParametersUseRoutineNameParameter() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_routine_parameters","params":{"params":{},"schema":"DEV2","routine_name":"FN_CUSTOMER_ORDER_COUNT"},"id":13}
                """));

        assertEquals(13, response.path("id").asInt());
        assertEquals("V_RET", response.path("result").path(0).path("name").asText());
        assertEquals("OUT", response.path("result").path(0).path("mode").asText());
        assertEquals(0, response.path("result").path(0).path("ordinal_position").asInt());
        assertEquals("P_CUSTOMER_ID", response.path("result").path(1).path("name").asText());
    }

    @Test
    void jdbcMethodsFailClearlyBeforeInitialize() throws Exception {
        RpcServer server = new RpcServer(new DamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"test_connection","params":{"params":{}},"id":9}
                """));

        assertEquals(-32602, response.path("error").path("code").asInt());
        assertTrue(response.path("error").path("message").asText().contains("not initialized"));
    }

    private static final class StubDamengClient extends DamengClient {
        @Override
        com.fasterxml.jackson.databind.node.ArrayNode getSchemaSnapshot(ConnectionParams params, String schema) {
            var snapshot = Json.NODES.arrayNode();
            var table = Json.NODES.objectNode();
            table.put("name", "CUSTOMERS");
            table.set("columns", Json.NODES.arrayNode());
            table.set("foreign_keys", Json.NODES.arrayNode());
            snapshot.add(table);
            return snapshot;
        }

        @Override
        com.fasterxml.jackson.databind.node.ObjectNode getAllForeignKeysBatch(ConnectionParams params, String schema) {
            var result = Json.NODES.objectNode();
            var keys = Json.NODES.arrayNode();
            var key = Json.NODES.objectNode();
            key.put("name", "FK_ORDERS_CUSTOMER");
            key.put("column_name", "CUSTOMER_ID");
            key.put("ref_table", "CUSTOMERS");
            key.put("ref_column", "ID");
            key.set("on_delete", Json.NODES.nullNode());
            key.set("on_update", Json.NODES.nullNode());
            keys.add(key);
            result.set("ORDERS", keys);
            return result;
        }

        @Override
        String getViewDefinition(ConnectionParams params, String view, String schema) throws SQLException {
            assertEquals("\"V_ORDER_SUMMARY\"", view);
            assertEquals("DEV2", schema);
            return "SELECT * FROM ORDERS";
        }

        @Override
        List<Map<String, JsonNode>> getIndexes(ConnectionParams params, String table, String schema) {
            Map<String, JsonNode> index = new LinkedHashMap<>();
            index.put("name", Json.NODES.textNode("IDX_ORDERS_CUSTOMER"));
            index.put("column_name", Json.NODES.textNode("CUSTOMER_ID"));
            index.put("is_unique", Json.NODES.booleanNode(false));
            index.put("is_primary", Json.NODES.booleanNode(false));
            index.put("seq_in_index", Json.NODES.numberNode(1));
            return List.of(index);
        }

        @Override
        List<Map<String, JsonNode>> getRoutines(ConnectionParams params, String schema) {
            Map<String, JsonNode> routine = new LinkedHashMap<>();
            routine.put("name", Json.NODES.textNode("FN_CUSTOMER_ORDER_COUNT"));
            routine.put("routine_type", Json.NODES.textNode("FUNCTION"));
            routine.put("definition", Json.NODES.textNode("FUNCTION FN_CUSTOMER_ORDER_COUNT(IN P_CUSTOMER_ID INT) RETURN INT"));
            return List.of(routine);
        }

        @Override
        List<Map<String, JsonNode>> getRoutineParameters(ConnectionParams params, String routineName, String schema) {
            assertEquals("FN_CUSTOMER_ORDER_COUNT", routineName);
            assertEquals("DEV2", schema);

            Map<String, JsonNode> returnValue = new LinkedHashMap<>();
            returnValue.put("name", Json.NODES.textNode("V_RET"));
            returnValue.put("data_type", Json.NODES.textNode("INT"));
            returnValue.put("mode", Json.NODES.textNode("OUT"));
            returnValue.put("ordinal_position", Json.NODES.numberNode(0));

            Map<String, JsonNode> customerId = new LinkedHashMap<>();
            customerId.put("name", Json.NODES.textNode("P_CUSTOMER_ID"));
            customerId.put("data_type", Json.NODES.textNode("INT"));
            customerId.put("mode", Json.NODES.textNode("IN"));
            customerId.put("ordinal_position", Json.NODES.numberNode(1));

            return List.of(returnValue, customerId);
        }
    }
}
