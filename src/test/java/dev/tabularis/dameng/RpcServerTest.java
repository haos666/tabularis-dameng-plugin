package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RpcServerTest {
    @Test
    void manifestAdvertisesTriggerCapabilityAndVersion() throws Exception {
        var manifest = Json.MAPPER.readTree(Files.readString(Path.of("manifest.json")));

        assertEquals("0.9.0", manifest.path("version").asText());
        assertTrue(manifest.path("capabilities").path("triggers").asBoolean());
        assertEquals("DM", manifest.path("name").asText());
        assertTrue(manifest.path("data_types").findValuesAsText("name").contains("VARBINARY"));
        assertTrue(manifest.path("data_types").findValuesAsText("name").contains("LONGVARCHAR"));
        assertTrue(manifest.path("data_types").findValuesAsText("name").contains("LONGVARBINARY"));
        assertTrue(manifest.path("data_types").findValuesAsText("name").contains("DOUBLE PRECISION"));
        assertTrue(manifest.path("data_types").findValuesAsText("name").contains("INTERVAL DAY TO SECOND"));
    }

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
    void batchMetadataAcceptsOptionalTablesParameter() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_all_columns_batch","params":{"params":{},"schema":"DEV2","tables":["ORDERS"]},"id":25}
                """));

        assertEquals(25, response.path("id").asInt());
        assertTrue(response.path("result").isObject());
        assertEquals("ID", response.path("result").path("ORDERS").path(0).path("name").asText());
        assertEquals("Order primary key", response.path("result").path("ORDERS").path(0).path("comment").asText());
    }

    @Test
    void tableMetadataDispatchesWithComments() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_tables","params":{"params":{},"schema":"DEV2"},"id":30}
                """));

        assertEquals(30, response.path("id").asInt());
        assertEquals("ORDERS", response.path("result").path(0).path("name").asText());
        assertEquals("DEV2", response.path("result").path(0).path("schema").asText());
        assertEquals("Customer orders", response.path("result").path(0).path("comment").asText());
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
    void protocolParameterAliasesDispatchToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var view = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_view_definition","params":{"params":{},"schema":"DEV2","view":"\\"V_ORDER_SUMMARY\\""},"id":26}
                """));
        var routine = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_routine_definition","params":{"params":{},"schema":"DEV2","routine":"FN_CUSTOMER_ORDER_COUNT","routine_type":"FUNCTION"},"id":27}
                """));
        var trigger = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_trigger_definition","params":{"params":{},"schema":"DEV2","trigger":"TRG_ORDERS_AUDIT","table_name":"ORDERS"},"id":28}
                """));
        var fk = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"drop_foreign_key","params":{"params":{},"schema":"DEV2","table":"ORDERS","constraint_name":"FK_ORDERS_CUSTOMER"},"id":29}
                """));

        assertEquals("SELECT * FROM ORDERS", view.path("result").asText());
        assertTrue(routine.path("result").asText().contains("FN_CUSTOMER_ORDER_COUNT"));
        assertTrue(trigger.path("result").asText().contains("TRG_ORDERS_AUDIT"));
        assertTrue(fk.path("result").isNull());
    }

    @Test
    void indexMetadataDispatchesToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_indexes","params":{"params":{},"schema":"DEV2","table":"ORDERS"},"id":11}
                """));

        assertEquals(11, response.path("id").asInt());
        assertEquals("IDX_ORDERS_CUSTOMER", response.path("result").path(0).path("name").asText());
        assertEquals("IDX_ORDERS_CUSTOMER", response.path("result").path(0).path("index_name").asText());
        assertEquals("CUSTOMER_ID", response.path("result").path(0).path("column_name").asText());
        assertEquals("CUSTOMER_ID", response.path("result").path(0).path("columns").path(0).asText());
    }

    @Test
    void foreignKeyMetadataDispatchesWithCompatibilityFields() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_foreign_keys","params":{"params":{},"schema":"DEV2","table":"ORDERS"},"id":31}
                """));

        assertEquals(31, response.path("id").asInt());
        assertEquals("FK_ORDERS_CUSTOMER", response.path("result").path(0).path("name").asText());
        assertEquals("FK_ORDERS_CUSTOMER", response.path("result").path(0).path("constraint_name").asText());
        assertEquals("CUSTOMERS", response.path("result").path(0).path("ref_table").asText());
        assertEquals("CUSTOMERS", response.path("result").path(0).path("referenced_table").asText());
        assertEquals("ID", response.path("result").path(0).path("ref_column").asText());
        assertEquals("ID", response.path("result").path(0).path("referenced_column").asText());
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
    void executeQueryBatchDispatchesToMetadataClient() throws Exception {
        RpcServer server = new RpcServer(new StubDamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"execute_query_batch","params":{"params":{},"schema":"DEV2","queries":["SELECT * FROM T_WRITE_TEST","BAD SQL"],"limit":25,"page":2},"id":24}
                """));

        assertEquals(24, response.path("id").asInt());
        assertTrue(response.path("result").isArray());
        assertTrue(response.path("result").path(0).path("error").isNull());
        assertEquals("NAME", response.path("result").path(0).path("result").path("columns").path(0).path("name").asText());
        assertTrue(response.path("result").path(0).path("execution_time_ms").isNumber());
        assertTrue(response.path("result").path(1).path("result").isNull());
        assertEquals("syntax error", response.path("result").path(1).path("error").asText());
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
                {"jsonrpc":"2.0","method":"get_create_table_sql","params":{"schema":"DEV2","table_name":"T_WRITE_TEST","comment":"Editable rows","columns":[{"name":"ID","data_type":"INT","is_nullable":false,"is_pk":true,"is_auto_increment":true,"default_value":null,"comment":"Primary identifier"},{"name":"NAME","data_type":"VARCHAR(80)","is_nullable":true,"is_pk":false,"is_auto_increment":false,"default_value":null,"comment":"Display name"}]},"id":21}
                """));

        assertEquals(21, response.path("id").asInt());
        assertTrue(response.path("result").isArray());
        assertTrue(response.path("result").path(0).asText().contains("CREATE TABLE \"DEV2\".\"T_WRITE_TEST\""));
        assertTrue(response.path("result").path(0).asText().contains("IDENTITY(1,1)"));
        assertEquals("COMMENT ON TABLE \"DEV2\".\"T_WRITE_TEST\" IS 'Editable rows'", response.path("result").path(1).asText());
        assertEquals("COMMENT ON COLUMN \"DEV2\".\"T_WRITE_TEST\".\"ID\" IS 'Primary identifier'", response.path("result").path(2).asText());
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
            var columns = Json.NODES.arrayNode();
            var column = Json.NODES.objectNode();
            column.put("name", "ID");
            column.put("comment", "Customer primary key");
            columns.add(column);
            table.set("columns", columns);
            table.set("foreign_keys", Json.NODES.arrayNode());
            snapshot.add(table);
            return snapshot;
        }

        @Override
        List<Map<String, JsonNode>> getTables(ConnectionParams params, String schema) {
            assertEquals("DEV2", schema);
            Map<String, JsonNode> table = new LinkedHashMap<>();
            table.put("name", Json.NODES.textNode("ORDERS"));
            table.put("schema", Json.NODES.textNode("DEV2"));
            table.put("comment", Json.NODES.textNode("Customer orders"));
            return List.of(table);
        }

        @Override
        com.fasterxml.jackson.databind.node.ObjectNode getAllColumnsBatch(ConnectionParams params, String schema, List<String> tables) {
            assertEquals("DEV2", schema);
            assertEquals(List.of("ORDERS"), tables);

            var result = Json.NODES.objectNode();
            var columns = Json.NODES.arrayNode();
            var column = Json.NODES.objectNode();
            column.put("name", "ID");
            column.put("data_type", "INT");
            column.put("is_pk", true);
            column.put("is_nullable", false);
            column.put("is_auto_increment", true);
            column.put("comment", "Order primary key");
            columns.add(column);
            result.set("ORDERS", columns);
            return result;
        }

        @Override
        com.fasterxml.jackson.databind.node.ObjectNode getAllForeignKeysBatch(ConnectionParams params, String schema, List<String> tables) {
            assertTrue(tables.isEmpty());
            var result = Json.NODES.objectNode();
            var keys = Json.NODES.arrayNode();
            var key = Json.NODES.objectNode();
            key.put("name", "FK_ORDERS_CUSTOMER");
            key.put("constraint_name", "FK_ORDERS_CUSTOMER");
            key.put("column_name", "CUSTOMER_ID");
            key.put("ref_table", "CUSTOMERS");
            key.put("referenced_table", "CUSTOMERS");
            key.put("ref_column", "ID");
            key.put("referenced_column", "ID");
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
            index.put("index_name", Json.NODES.textNode("IDX_ORDERS_CUSTOMER"));
            index.put("column_name", Json.NODES.textNode("CUSTOMER_ID"));
            var columns = Json.NODES.arrayNode();
            columns.add("CUSTOMER_ID");
            index.put("columns", columns);
            index.put("is_unique", Json.NODES.booleanNode(false));
            index.put("is_primary", Json.NODES.booleanNode(false));
            index.put("seq_in_index", Json.NODES.numberNode(1));
            return List.of(index);
        }

        @Override
        List<Map<String, JsonNode>> getForeignKeys(ConnectionParams params, String table, String schema) {
            assertEquals("ORDERS", table);
            assertEquals("DEV2", schema);
            Map<String, JsonNode> key = new LinkedHashMap<>();
            key.put("name", Json.NODES.textNode("FK_ORDERS_CUSTOMER"));
            key.put("constraint_name", Json.NODES.textNode("FK_ORDERS_CUSTOMER"));
            key.put("column_name", Json.NODES.textNode("CUSTOMER_ID"));
            key.put("ref_table", Json.NODES.textNode("CUSTOMERS"));
            key.put("referenced_table", Json.NODES.textNode("CUSTOMERS"));
            key.put("ref_column", Json.NODES.textNode("ID"));
            key.put("referenced_column", Json.NODES.textNode("ID"));
            key.put("on_delete", Json.NODES.nullNode());
            key.put("on_update", Json.NODES.nullNode());
            return List.of(key);
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
        com.fasterxml.jackson.databind.node.ArrayNode executeQueryBatch(ConnectionParams params, List<String> queries, Integer limit, int page, String schema) {
            assertEquals(List.of("SELECT * FROM T_WRITE_TEST", "BAD SQL"), queries);
            assertEquals(25, limit);
            assertEquals(2, page);
            assertEquals("DEV2", schema);

            var results = Json.NODES.arrayNode();

            var success = Json.NODES.objectNode();
            var result = Json.NODES.objectNode();
            var columns = Json.NODES.arrayNode();
            var column = Json.NODES.objectNode();
            column.put("name", "NAME");
            column.put("data_type", "VARCHAR");
            columns.add(column);
            result.set("columns", columns);
            result.set("rows", Json.NODES.arrayNode());
            result.put("affected_rows", 0);
            result.put("truncated", false);
            result.set("pagination", Json.NODES.nullNode());
            success.set("result", result);
            success.set("error", Json.NODES.nullNode());
            success.put("execution_time_ms", 1.25);
            results.add(success);

            var failure = Json.NODES.objectNode();
            failure.set("result", Json.NODES.nullNode());
            failure.put("error", "syntax error");
            failure.put("execution_time_ms", 0.5);
            results.add(failure);

            return results;
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

        @Override
        void dropForeignKey(ConnectionParams params, String table, String fkName, String schema) {
            assertEquals("ORDERS", table);
            assertEquals("FK_ORDERS_CUSTOMER", fkName);
            assertEquals("DEV2", schema);
        }
    }
}
