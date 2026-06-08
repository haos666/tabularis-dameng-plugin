package dev.tabularis.dameng;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class DamengClient {
    private static final String DRIVER_CLASS = "dm.jdbc.driver.DmDriver";
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "SYS", "SYSAUDITOR", "SYSSSO", "SYSJOB", "CTISYS", "SYSDBO",
            "SYSINFO", "SYSGEO", "SYSMAN", "SYSDG", "SYSCAT", "SYSOBJECTS", "SYSWEB"
    );

    private PluginSettings settings;
    private Driver driver;
    private URLClassLoader driverClassLoader;

    synchronized void initialize(PluginSettings newSettings) throws Exception {
        if (!Files.isRegularFile(newSettings.jdbcDriverPath())) {
            throw new RpcException(-32602, "Dameng JDBC driver jar not found: " + newSettings.jdbcDriverPath());
        }

        URL jarUrl = newSettings.jdbcDriverPath().toUri().toURL();
        URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, DamengClient.class.getClassLoader());
        try {
            Class<?> clazz = Class.forName(DRIVER_CLASS, true, loader);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Driver loadedDriver)) {
                throw new RpcException(-32603, DRIVER_CLASS + " does not implement java.sql.Driver.");
            }

            URLClassLoader oldLoader = this.driverClassLoader;
            this.settings = newSettings;
            this.driver = loadedDriver;
            this.driverClassLoader = loader;
            closeQuietly(oldLoader);
            System.err.println("Loaded Dameng JDBC driver from " + newSettings.jdbcDriverPath());
        } catch (Exception e) {
            closeQuietly(loader);
            throw e;
        }
    }

    void testConnection(ConnectionParams params) throws SQLException {
        ping(params);
    }

    void ping(ConnectionParams params) throws SQLException {
        try (Connection conn = connect(params);
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            stmt.execute("SELECT 1");
        }
    }

    List<String> getSchemas(ConnectionParams params) throws SQLException {
        try (Connection conn = connect(params)) {
            String currentUser = currentUser(conn);
            try (PreparedStatement stmt = conn.prepareStatement("""
                     SELECT USERNAME
                     FROM ALL_USERS
                     ORDER BY USERNAME
                     """)) {
                stmt.setQueryTimeout(settings().queryTimeoutSec());
                try (ResultSet rs = stmt.executeQuery()) {
                    LinkedHashSet<String> schemas = new LinkedHashSet<>();
                    if (currentUser != null && !currentUser.isBlank()) {
                        schemas.add(currentUser);
                    }
                    while (rs.next()) {
                        String name = rs.getString(1);
                        if (name != null && shouldShowSchema(name, currentUser)) {
                            schemas.add(name.toUpperCase(Locale.ROOT));
                        }
                    }
                    return new ArrayList<>(schemas);
                }
            }
        }
    }

    List<String> getTables(ConnectionParams params, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT TABLE_NAME
                    FROM ALL_TABLES
                    WHERE OWNER = ?
                    ORDER BY TABLE_NAME
                    """)) {
                stmt.setQueryTimeout(settings().queryTimeoutSec());
                stmt.setString(1, owner);
                try (ResultSet rs = stmt.executeQuery()) {
                    List<String> tables = new ArrayList<>();
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                    return tables;
                }
            }
        }
    }

    List<Map<String, JsonNode>> getColumns(ConnectionParams params, String table, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT
                        c.COLUMN_NAME,
                        c.DATA_TYPE,
                        c.NULLABLE,
                        c.DATA_DEFAULT,
                        c.CHAR_LENGTH,
                        CASE WHEN pk.COLUMN_NAME IS NULL THEN 0 ELSE 1 END AS IS_PK
                    FROM ALL_TAB_COLUMNS c
                    LEFT JOIN (
                        SELECT acc.OWNER, acc.TABLE_NAME, acc.COLUMN_NAME
                        FROM ALL_CONSTRAINTS ac
                        JOIN ALL_CONS_COLUMNS acc
                          ON ac.OWNER = acc.OWNER
                         AND ac.CONSTRAINT_NAME = acc.CONSTRAINT_NAME
                        WHERE ac.CONSTRAINT_TYPE = 'P'
                    ) pk
                      ON pk.OWNER = c.OWNER
                     AND pk.TABLE_NAME = c.TABLE_NAME
                     AND pk.COLUMN_NAME = c.COLUMN_NAME
                    WHERE c.OWNER = ?
                      AND c.TABLE_NAME = ?
                    ORDER BY c.COLUMN_ID
                    """)) {
                stmt.setQueryTimeout(settings().queryTimeoutSec());
                stmt.setString(1, owner);
                stmt.setString(2, normalizeIdentifier(table));
                try (ResultSet rs = stmt.executeQuery()) {
                    List<Map<String, JsonNode>> columns = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, JsonNode> item = new LinkedHashMap<>();
                        item.put("name", Json.NODES.textNode(rs.getString("COLUMN_NAME")));
                        item.put("data_type", Json.NODES.textNode(rs.getString("DATA_TYPE")));
                        item.put("is_pk", Json.NODES.booleanNode(rs.getInt("IS_PK") == 1));
                        item.put("is_nullable", Json.NODES.booleanNode("Y".equalsIgnoreCase(rs.getString("NULLABLE"))));
                        item.put("is_auto_increment", Json.NODES.booleanNode(false));
                        String defaultValue = rs.getString("DATA_DEFAULT");
                        item.put("default_value", defaultValue == null ? Json.NODES.nullNode() : Json.NODES.textNode(defaultValue.strip()));
                        long charLength = rs.getLong("CHAR_LENGTH");
                        item.put("character_maximum_length", rs.wasNull() ? Json.NODES.nullNode() : Json.NODES.numberNode(charLength));
                        columns.add(item);
                    }
                    return columns;
                }
            }
        }
    }

    ObjectNode executeQuery(ConnectionParams params, String query, Integer limit, int page) throws SQLException {
        SqlSafety.requireReadOnly(query);
        String finalQuery = limit == null ? query : QueryPaginator.paginated(query, limit, page);
        try (Connection conn = connect(params);
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            boolean hasResultSet = stmt.execute(finalQuery);
            if (!hasResultSet) {
                ObjectNode result = Json.NODES.objectNode();
                result.set("columns", Json.NODES.arrayNode());
                result.set("rows", Json.NODES.arrayNode());
                result.put("affected_rows", 0);
                result.put("truncated", false);
                result.set("pagination", Json.NODES.nullNode());
                return result;
            }
            try (ResultSet rs = stmt.getResultSet()) {
                return ResultSetJson.toQueryResult(rs, limit, page);
            }
        }
    }

    private Connection connect(ConnectionParams params) throws SQLException {
        PluginSettings current = settings();
        Connection conn = driver.connect(params.jdbcUrl(), params.properties(current.connectTimeoutMs()));
        if (conn == null) {
            throw new SQLException("Dameng JDBC driver refused URL: " + params.jdbcUrl());
        }
        conn.setReadOnly(true);
        return conn;
    }

    private PluginSettings settings() {
        if (settings == null || driver == null || driverClassLoader == null) {
            throw new RpcException(-32602, "Dameng plugin is not initialized. Configure 'jdbc_driver_path' in plugin settings first.");
        }
        return settings;
    }

    private String resolveSchema(Connection conn, String schema) throws SQLException {
        if (schema != null && !schema.isBlank()) {
            return normalizeIdentifier(schema);
        }
        return currentUser(conn);
    }

    private String currentUser(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT USER")) {
            if (rs.next()) {
                return rs.getString(1).toUpperCase(Locale.ROOT);
            }
        }
        throw new SQLException("Could not resolve current Dameng schema.");
    }

    private boolean shouldShowSchema(String schema, String currentUser) {
        String normalized = schema.toUpperCase(Locale.ROOT);
        return normalized.equals(currentUser) || !SYSTEM_SCHEMAS.contains(normalized);
    }

    private static String normalizeIdentifier(String value) {
        String trimmed = value.strip();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (Exception ignored) {
        }
    }
}
