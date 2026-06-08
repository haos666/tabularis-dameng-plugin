package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

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
    void schemaSnapshotReturnsRuntimeCompatibleArray() throws Exception {
        RpcServer server = new RpcServer(new DamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_schema_snapshot","params":{},"id":7}
                """));

        assertEquals(7, response.path("id").asInt());
        assertTrue(response.path("result").isArray());
    }

    @Test
    void batchMetadataMethodsReturnRuntimeCompatibleObjects() throws Exception {
        RpcServer server = new RpcServer(new DamengClient());

        var response = Json.MAPPER.readTree(server.handleLine("""
                {"jsonrpc":"2.0","method":"get_all_foreign_keys_batch","params":{},"id":8}
                """));

        assertEquals(8, response.path("id").asInt());
        assertTrue(response.path("result").isObject());
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
}
