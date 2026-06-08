package dev.tabularis.dameng;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

class DamengClient {
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
            return getColumns(conn, owner, table);
        }
    }

    List<Map<String, JsonNode>> getIndexes(ConnectionParams params, String table, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT
                        ic.INDEX_NAME,
                        ic.COLUMN_NAME,
                        ix.UNIQUENESS,
                        CASE WHEN pk.CONSTRAINT_NAME IS NULL THEN 0 ELSE 1 END AS IS_PRIMARY,
                        ic.COLUMN_POSITION
                    FROM ALL_IND_COLUMNS ic
                    JOIN ALL_INDEXES ix
                      ON ix.OWNER = ic.INDEX_OWNER
                     AND ix.INDEX_NAME = ic.INDEX_NAME
                    LEFT JOIN ALL_CONSTRAINTS pk
                      ON pk.OWNER = ix.OWNER
                     AND pk.INDEX_NAME = ix.INDEX_NAME
                     AND pk.CONSTRAINT_TYPE = 'P'
                    WHERE ic.TABLE_OWNER = ?
                      AND ic.TABLE_NAME = ?
                    ORDER BY ic.INDEX_NAME, ic.COLUMN_POSITION
                    """)) {
                stmt.setQueryTimeout(settings().queryTimeoutSec());
                stmt.setString(1, owner);
                stmt.setString(2, normalizeIdentifier(table));
                try (ResultSet rs = stmt.executeQuery()) {
                    List<Map<String, JsonNode>> indexes = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, JsonNode> item = new LinkedHashMap<>();
                        item.put("name", Json.NODES.textNode(rs.getString("INDEX_NAME")));
                        item.put("column_name", Json.NODES.textNode(rs.getString("COLUMN_NAME")));
                        item.put("is_unique", Json.NODES.booleanNode("UNIQUE".equalsIgnoreCase(rs.getString("UNIQUENESS"))));
                        item.put("is_primary", Json.NODES.booleanNode(rs.getInt("IS_PRIMARY") == 1));
                        item.put("seq_in_index", Json.NODES.numberNode(rs.getInt("COLUMN_POSITION")));
                        indexes.add(item);
                    }
                    return indexes;
                }
            }
        }
    }

    List<Map<String, JsonNode>> getForeignKeys(ConnectionParams params, String table, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            return getForeignKeys(conn, owner, table);
        }
    }

    List<Map<String, JsonNode>> getViews(ConnectionParams params, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT VIEW_NAME
                    FROM ALL_VIEWS
                    WHERE OWNER = ?
                    ORDER BY VIEW_NAME
                    """)) {
                stmt.setQueryTimeout(settings().queryTimeoutSec());
                stmt.setString(1, owner);
                try (ResultSet rs = stmt.executeQuery()) {
                    List<Map<String, JsonNode>> views = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, JsonNode> item = new LinkedHashMap<>();
                        item.put("name", Json.NODES.textNode(rs.getString("VIEW_NAME")));
                        item.put("definition", Json.NODES.nullNode());
                        views.add(item);
                    }
                    return views;
                }
            }
        }
    }

    String getViewDefinition(ConnectionParams params, String view, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT TEXT
                    FROM ALL_VIEWS
                    WHERE OWNER = ?
                      AND VIEW_NAME = ?
                    """)) {
                stmt.setQueryTimeout(settings().queryTimeoutSec());
                stmt.setString(1, owner);
                stmt.setString(2, normalizeIdentifier(view));
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String text = rs.getString("TEXT");
                        return text == null ? "" : text;
                    }
                    return "";
                }
            }
        }
    }

    List<Map<String, JsonNode>> getViewColumns(ConnectionParams params, String view, String schema) throws SQLException {
        return getColumns(params, view, schema);
    }

    ObjectNode getAllColumnsBatch(ConnectionParams params, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            ObjectNode result = Json.NODES.objectNode();
            for (String table : getTables(conn, owner)) {
                result.set(table, columnsArray(getColumns(conn, owner, table)));
            }
            return result;
        }
    }

    ObjectNode getAllForeignKeysBatch(ConnectionParams params, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            ObjectNode result = Json.NODES.objectNode();
            for (String table : getTables(conn, owner)) {
                result.set(table, columnsArray(getForeignKeys(conn, owner, table)));
            }
            return result;
        }
    }

    ArrayNode getSchemaSnapshot(ConnectionParams params, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            ArrayNode snapshot = Json.NODES.arrayNode();
            for (String table : getTables(conn, owner)) {
                ObjectNode item = Json.NODES.objectNode();
                item.put("name", table);
                item.set("columns", columnsArray(getColumns(conn, owner, table)));
                item.set("foreign_keys", columnsArray(getForeignKeys(conn, owner, table)));
                snapshot.add(item);
            }
            return snapshot;
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

    private List<String> getTables(Connection conn, String owner) throws SQLException {
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

    private List<Map<String, JsonNode>> getColumns(Connection conn, String owner, String table) throws SQLException {
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
                    item.put("character_maximum_length", rs.wasNull() || charLength <= 0 ? Json.NODES.nullNode() : Json.NODES.numberNode(charLength));
                    columns.add(item);
                }
                return columns;
            }
        }
    }

    private List<Map<String, JsonNode>> getForeignKeys(Connection conn, String owner, String table) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("""
                SELECT
                    child.CONSTRAINT_NAME,
                    child_cols.COLUMN_NAME,
                    parent_cols.TABLE_NAME AS REF_TABLE,
                    parent_cols.COLUMN_NAME AS REF_COLUMN,
                    child.DELETE_RULE
                FROM ALL_CONSTRAINTS child
                JOIN ALL_CONS_COLUMNS child_cols
                  ON child.OWNER = child_cols.OWNER
                 AND child.CONSTRAINT_NAME = child_cols.CONSTRAINT_NAME
                JOIN ALL_CONSTRAINTS parent
                  ON child.R_OWNER = parent.OWNER
                 AND child.R_CONSTRAINT_NAME = parent.CONSTRAINT_NAME
                JOIN ALL_CONS_COLUMNS parent_cols
                  ON parent.OWNER = parent_cols.OWNER
                 AND parent.CONSTRAINT_NAME = parent_cols.CONSTRAINT_NAME
                 AND child_cols.POSITION = parent_cols.POSITION
                WHERE child.OWNER = ?
                  AND child.TABLE_NAME = ?
                  AND child.CONSTRAINT_TYPE = 'R'
                ORDER BY child.CONSTRAINT_NAME, child_cols.POSITION
                """)) {
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            stmt.setString(1, owner);
            stmt.setString(2, normalizeIdentifier(table));
            try (ResultSet rs = stmt.executeQuery()) {
                List<Map<String, JsonNode>> fks = new ArrayList<>();
                while (rs.next()) {
                    Map<String, JsonNode> item = new LinkedHashMap<>();
                    item.put("name", Json.NODES.textNode(rs.getString("CONSTRAINT_NAME")));
                    item.put("column_name", Json.NODES.textNode(rs.getString("COLUMN_NAME")));
                    item.put("ref_table", Json.NODES.textNode(rs.getString("REF_TABLE")));
                    item.put("ref_column", Json.NODES.textNode(rs.getString("REF_COLUMN")));
                    item.put("on_delete", nullableText(rs.getString("DELETE_RULE")));
                    item.put("on_update", Json.NODES.nullNode());
                    fks.add(item);
                }
                return fks;
            }
        }
    }

    private static ArrayNode columnsArray(List<Map<String, JsonNode>> items) {
        ArrayNode array = Json.NODES.arrayNode();
        for (Map<String, JsonNode> item : items) {
            ObjectNode object = Json.NODES.objectNode();
            item.forEach(object::set);
            array.add(object);
        }
        return array;
    }

    private static JsonNode nullableText(String value) {
        return value == null || value.isBlank() ? Json.NODES.nullNode() : Json.NODES.textNode(value);
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

    static String normalizeIdentifier(String value) {
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
