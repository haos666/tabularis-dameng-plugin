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
    void routineDefinitionDispatchesToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_routine_definition","params":{"params":{},"schema":"DEV2","routine_name":"FN_CUSTOMER_ORDER_COUNT","routine_type":"FUNCTION"},"id":14}
                """));

        assertEquals(14, response.path("id").asInt());
        assertEquals("FUNCTION FN_CUSTOMER_ORDER_COUNT(IN P_CUSTOMER_ID INT) RETURN INT", response.path("result").asText());
    }

    @Test
    void explainQueryDispatchesToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"explain_query","params":{"params":{},"schema":"DEV2","query":"SELECT * FROM ORDERS","analyze":true},"id":15}
                """));

        assertEquals(15, response.path("id").asInt());
        assertEquals("dameng", response.path("result").path("driver").asText());
        assertEquals("#NSET2", response.path("result").path("root").path("node_type").asText());
    }

    @Test
    void triggerMetadataDispatchesToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_triggers","params":{"params":{},"schema":"DEV2"},"id":16}
                """));

        assertEquals(16, response.path("id").asInt());
        assertEquals("TRG_ORDERS_AUDIT", response.path("result").path(0).path("name").asText());
        assertEquals("ORDERS", response.path("result").path(0).path("table_name").asText());
    }

    @Test
    void triggerDefinitionDispatchesToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_trigger_definition","params":{"params":{},"schema":"DEV2","trigger_name":"TRG_ORDERS_AUDIT","table_name":"ORDERS"},"id":17}
                """));

        assertEquals(17, response.path("id").asInt());
        assertTrue(response.path("result").asText().contains("TRG_ORDERS_AUDIT"));
    }

    @Test
    void crudMethodsDispatchToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var insert = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"insert_record","params":{"params":{},"schema":"DEV2","table":"T_WRITE_TEST","data":{"name":"Alice","score":7}},"id":18}
                """));
        var update = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"update_record","params":{"params":{},"schema":"DEV2","table":"T_WRITE_TEST","pk_col":"ID","pk_val":1,"col_name":"NAME","new_val":"Bob"},"id":19}
                """));
        var delete = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"delete_record","params":{"params":{},"schema":"DEV2","table":"T_WRITE_TEST","pk_col":"ID","pk_val":1},"id":20}
                """));

        assertEquals(1, insert.path("result").asInt());
        assertEquals(1, update.path("result").asInt());
        assertEquals(1, delete.path("result").asInt());
    }

    @Test
    void ddlPreviewMethodsReturnStatementArrays() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_create_table_sql","params":{"schema":"DEV2","table_name":"T_WRITE_TEST","columns":[{"name":"ID","data_type":"INT","is_nullable":false,"is_pk":true,"is_auto_increment":true,"default_value":null},{"name":"NAME","data_type":"VARCHAR(80)","is_nullable":true,"is_pk":false,"is_auto_increment":false,"default_value":null}]},"id":21}
                """));

        assertEquals(21, response.path("id").asInt());
        assertTrue(response.path("result").isArray());
        assertTrue(response.path("result").path(0).asText().contains("CREATE TABLE \"DEV2\".\"T_WRITE_TEST\""));
        assertTrue(response.path("result").path(0).asText().contains("IDENTITY(1,1)"));
    }

    @Test
    void viewAndTriggerWriteMethodsDispatchToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var view = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"create_view","params":{"params":{},"schema":"DEV2","view_name":"V_WRITE_TEST","definition":"SELECT 1 AS ID"},"id":22}
                """));
        var trigger = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"create_trigger","params":{"params":{},"schema":"DEV2","trigger_sql":"CREATE TRIGGER TRG_WRITE_TEST AFTER UPDATE ON T_WRITE_TEST BEGIN NULL; END;"},"id":23}
                """));

        assertTrue(view.path("result").isNull());
        assertTrue(trigger.path("result").isNull());
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

        @Override
        String getRoutineDefinition(ConnectionParams params, String routineName, String routineType, String schema) {
            assertEquals("FN_CUSTOMER_ORDER_COUNT", routineName);
            assertEquals("FUNCTION", routineType);
            assertEquals("DEV2", schema);
            return "FUNCTION FN_CUSTOMER_ORDER_COUNT(IN P_CUSTOMER_ID INT) RETURN INT";
        }

        @Override
        com.fasterxml.jackson.databind.node.ObjectNode explainQuery(ConnectionParams params, String query, boolean analyze, String schema) {
            assertEquals("SELECT * FROM ORDERS", query);
            assertTrue(analyze);
            assertEquals("DEV2", schema);
            return ExplainParser.toExplainPlan("1   #NSET2: [1, 1, 10]", query);
        }

        @Override
        List<Map<String, JsonNode>> getTriggers(ConnectionParams params, String schema) {
            Map<String, JsonNode> trigger = new LinkedHashMap<>();
            trigger.put("name", Json.NODES.textNode("TRG_ORDERS_AUDIT"));
            trigger.put("table_name", Json.NODES.textNode("ORDERS"));
            trigger.put("event", Json.NODES.textNode("UPDATE"));
            trigger.put("timing", Json.NODES.textNode("AFTER"));
            trigger.put("definition", Json.NODES.textNode("TRIGGER TRG_ORDERS_AUDIT AFTER UPDATE ON ORDERS"));
            return List.of(trigger);
        }

        @Override
        String getTriggerDefinition(ConnectionParams params, String triggerName, String tableName, String schema) {
            assertEquals("TRG_ORDERS_AUDIT", triggerName);
            assertEquals("ORDERS", tableName);
            assertEquals("DEV2", schema);
            return "TRIGGER TRG_ORDERS_AUDIT AFTER UPDATE ON ORDERS";
        }

        @Override
        long insertRecord(ConnectionParams params, String table, JsonNode data, String schema, long maxBlobSize) {
            assertEquals("T_WRITE_TEST", table);
            assertEquals("Alice", data.path("name").asText());
            assertEquals("DEV2", schema);
            return 1;
        }

        @Override
        long updateRecord(ConnectionParams params, String table, String pkCol, JsonNode pkVal, String colName, JsonNode newVal, String schema, long maxBlobSize) {
            assertEquals("T_WRITE_TEST", table);
            assertEquals("ID", pkCol);
            assertEquals(1, pkVal.asInt());
            assertEquals("NAME", colName);
            assertEquals("Bob", newVal.asText());
            assertEquals("DEV2", schema);
            return 1;
        }

        @Override
        long deleteRecord(ConnectionParams params, String table, String pkCol, JsonNode pkVal, String schema) {
            assertEquals("T_WRITE_TEST", table);
            assertEquals("ID", pkCol);
            assertEquals(1, pkVal.asInt());
            assertEquals("DEV2", schema);
            return 1;
        }

        @Override
        void createView(ConnectionParams params, String viewName, String definition, String schema) {
            assertEquals("V_WRITE_TEST", viewName);
            assertEquals("SELECT 1 AS ID", definition);
            assertEquals("DEV2", schema);
        }

        @Override
        void createTrigger(ConnectionParams params, String triggerSql, String schema) {
            assertTrue(triggerSql.contains("TRG_WRITE_TEST"));
            assertEquals("DEV2", schema);
        }
    }
}
