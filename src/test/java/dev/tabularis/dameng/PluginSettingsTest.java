package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PluginSettingsTest {
    @Test
    void readsInitializeSettings() throws Exception {
        var params = Json.MAPPER.readTree("""
                {
                  "settings": {
                    "jdbc_driver_path": "/tmp/DmJdbcDriver8.jar",
                    "connect_timeout_ms": 1234,
                    "query_timeout_sec": "9"
                  }
                }
                """);

        PluginSettings settings = PluginSettings.fromInitializeParams(params);

        assertEquals("/tmp/DmJdbcDriver8.jar", settings.jdbcDriverPath().toString());
        assertEquals(1234, settings.connectTimeoutMs());
        assertEquals(9, settings.queryTimeoutSec());
    }

    @Test
    void rejectsMissingJdbcDriverPath() throws Exception {
        var params = Json.MAPPER.readTree("{\"settings\":{}}");

        RpcException error = assertThrows(RpcException.class, () -> PluginSettings.fromInitializeParams(params));

        assertEquals(-32602, error.code());
    }

    @Test
    void fallsBackForNonPositiveTimeouts() throws Exception {
        var params = Json.MAPPER.readTree("""
                {
                  "settings": {
                    "jdbc_driver_path": "/tmp/DmJdbcDriver8.jar",
                    "connect_timeout_ms": 0,
                    "query_timeout_sec": -1
                  }
                }
                """);

        PluginSettings settings = PluginSettings.fromInitializeParams(params);

        assertEquals(10000, settings.connectTimeoutMs());
        assertEquals(60, settings.queryTimeoutSec());
    }
}
