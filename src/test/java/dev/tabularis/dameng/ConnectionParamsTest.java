package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConnectionParamsTest {
    @Test
    void buildsDamengJdbcUrlWithDatabase() throws Exception {
        var node = Json.MAPPER.readTree("""
                {
                  "host": "127.0.0.1",
                  "port": 5237,
                  "database": "DAMENG",
                  "username": "SYSDBA",
                  "password": "secret"
                }
                """);

        ConnectionParams params = ConnectionParams.from(node);

        assertEquals("jdbc:dm://127.0.0.1:5237", params.jdbcUrl());
        assertEquals("SYSDBA", params.properties(10000).getProperty("user"));
        assertEquals("secret", params.properties(10000).getProperty("password"));
    }

    @Test
    void acceptsTabularisMultiDatabaseShape() throws Exception {
        var node = Json.MAPPER.readTree("""
                { "host": "localhost", "database": ["A", "B"] }
                """);

        assertEquals("jdbc:dm://localhost:5236", ConnectionParams.from(node).jdbcUrl());
    }
}
