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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Clob;
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
            } catch (SQLException e) {
                if (currentUser != null && !currentUser.isBlank()) {
                    return List.of(currentUser);
                }
                throw e;
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
                        String text = textValue(rs, "TEXT");
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

    ObjectNode getAllColumnsBatch(ConnectionParams params, String schema, List<String> requestedTables) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            ObjectNode result = Json.NODES.objectNode();
            for (String table : requestedTables(getTables(conn, owner), requestedTables)) {
                result.set(table, columnsArray(getColumns(conn, owner, table)));
            }
            return result;
        }
    }

    ObjectNode getAllForeignKeysBatch(ConnectionParams params, String schema, List<String> requestedTables) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            ObjectNode result = Json.NODES.objectNode();
            for (String table : requestedTables(getTables(conn, owner), requestedTables)) {
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

    List<Map<String, JsonNode>> getRoutines(ConnectionParams params, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            Map<String, String> definitions = getRoutineDefinitions(conn, owner);
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT OBJECT_NAME, OBJECT_TYPE
                    FROM ALL_PROCEDURES
                    WHERE OWNER = ?
                      AND PROCEDURE_NAME IS NULL
                      AND OBJECT_TYPE IN ('FUNCTION', 'PROCEDURE')
                    ORDER BY OBJECT_TYPE, OBJECT_NAME
                    """)) {
                stmt.setQueryTimeout(settings().queryTimeoutSec());
                stmt.setString(1, owner);
                try (ResultSet rs = stmt.executeQuery()) {
                    List<Map<String, JsonNode>> routines = new ArrayList<>();
                    while (rs.next()) {
                        String name = rs.getString("OBJECT_NAME");
                        String type = normalizeRoutineType(rs.getString("OBJECT_TYPE"));
                        String definition = definitions.get(name);
                        if (definition == null || definition.isBlank()) {
                            definition = routineSignature(type, name, getRoutineParameters(conn, owner, name));
                        }

                        Map<String, JsonNode> item = new LinkedHashMap<>();
                        item.put("name", Json.NODES.textNode(name));
                        item.put("routine_type", Json.NODES.textNode(type));
                        item.put("definition", definition == null || definition.isBlank()
                                ? Json.NODES.nullNode()
                                : Json.NODES.textNode(definition));
                        routines.add(item);
                    }
                    return routines;
                }
            }
        }
    }

    List<Map<String, JsonNode>> getRoutineParameters(ConnectionParams params, String routineName, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            return getRoutineParameters(conn, owner, routineName);
        }
    }

    String getRoutineDefinition(ConnectionParams params, String routineName, String routineType, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            String name = normalizeIdentifier(routineName);
            String definition = getRoutineDefinitions(conn, owner).get(name);
            if (definition != null && !definition.isBlank()) {
                return definition;
            }
            return routineSignature(routineType, name, getRoutineParameters(conn, owner, name));
        }
    }

    List<Map<String, JsonNode>> getTriggers(ConnectionParams params, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT TRIGGER_NAME, TABLE_NAME, TRIGGERING_EVENT, TRIGGERING_TYPE, DESCRIPTION, TRIGGER_BODY
                    FROM ALL_TRIGGERS
                    WHERE OWNER = ?
                    ORDER BY TABLE_NAME, TRIGGER_NAME
                    """)) {
                stmt.setQueryTimeout(settings().queryTimeoutSec());
                stmt.setString(1, owner);
                try (ResultSet rs = stmt.executeQuery()) {
                    List<Map<String, JsonNode>> triggers = new ArrayList<>();
                    while (rs.next()) {
                        String name = rs.getString("TRIGGER_NAME");
                        String tableName = rs.getString("TABLE_NAME");
                        String event = normalizeTriggerEvent(rs.getString("TRIGGERING_EVENT"));
                        String timing = normalizeTriggerTiming(rs.getString("TRIGGERING_TYPE"));
                        String definition = triggerDefinition(
                                rs.getString("DESCRIPTION"),
                                textValue(rs, "TRIGGER_BODY"),
                                name,
                                tableName,
                                timing,
                                event
                        );

                        Map<String, JsonNode> item = new LinkedHashMap<>();
                        item.put("name", Json.NODES.textNode(name));
                        item.put("table_name", Json.NODES.textNode(tableName));
                        item.put("event", Json.NODES.textNode(event));
                        item.put("timing", Json.NODES.textNode(timing));
                        item.put("definition", definition == null || definition.isBlank()
                                ? Json.NODES.nullNode()
                                : Json.NODES.textNode(definition));
                        triggers.add(item);
                    }
                    return triggers;
                }
            }
        }
    }

    String getTriggerDefinition(ConnectionParams params, String triggerName, String tableName, String schema) throws SQLException {
        try (Connection conn = connect(params)) {
            String owner = resolveSchema(conn, schema);
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT TRIGGER_NAME, TABLE_NAME, TRIGGERING_EVENT, TRIGGERING_TYPE, DESCRIPTION, TRIGGER_BODY
                    FROM ALL_TRIGGERS
                    WHERE OWNER = ?
                      AND TRIGGER_NAME = ?
                      AND TABLE_NAME = ?
                    """)) {
                stmt.setQueryTimeout(settings().queryTimeoutSec());
                stmt.setString(1, owner);
                stmt.setString(2, normalizeIdentifier(triggerName));
                stmt.setString(3, normalizeIdentifier(tableName));
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return triggerDefinition(
                                rs.getString("DESCRIPTION"),
                                textValue(rs, "TRIGGER_BODY"),
                                rs.getString("TRIGGER_NAME"),
                                rs.getString("TABLE_NAME"),
                                normalizeTriggerTiming(rs.getString("TRIGGERING_TYPE")),
                                normalizeTriggerEvent(rs.getString("TRIGGERING_EVENT"))
                        );
                    }
                }
            }
            return triggerSignature(normalizeIdentifier(triggerName), normalizeIdentifier(tableName), "", "");
        }
    }

    ObjectNode executeQuery(ConnectionParams params, String query, Integer limit, int page, String schema) throws SQLException {
        try (Connection conn = connect(params, false);
             Statement stmt = conn.createStatement()) {
            applySchema(conn, schema);
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            return executeOne(stmt, query, limit, page);
        }
    }

    ArrayNode executeQueryBatch(ConnectionParams params, List<String> queries, Integer limit, int page, String schema) throws SQLException {
        try (Connection conn = connect(params, false);
             Statement stmt = conn.createStatement()) {
            applySchema(conn, schema);
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            ArrayNode results = Json.NODES.arrayNode();
            for (String query : queries) {
                long started = System.nanoTime();
                ObjectNode item = Json.NODES.objectNode();
                try {
                    item.set("result", executeOne(stmt, query, limit, page));
                    item.set("error", Json.NODES.nullNode());
                } catch (Exception e) {
                    item.set("result", Json.NODES.nullNode());
                    item.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                item.put("execution_time_ms", elapsedMillis(started));
                results.add(item);
            }
            return results;
        }
    }

    long insertRecord(ConnectionParams params, String table, JsonNode data, String schema, long maxBlobSize) throws SQLException {
        if (data == null || !data.isObject() || data.isEmpty()) {
            throw new RpcException(-32602, "insert_record requires non-empty object 'data'.");
        }
        List<String> columns = new ArrayList<>();
        data.fieldNames().forEachRemaining(columns::add);
        String placeholders = "?,".repeat(columns.size());
        placeholders = placeholders.substring(0, placeholders.length() - 1);
        String sql = "INSERT INTO " + DamengSql.qualifiedName(table, schema)
                + " (" + columns.stream().map(DamengSql::quoteIdentifier).reduce((a, b) -> a + ", " + b).orElse("") + ")"
                + " VALUES (" + placeholders + ")";

        try (Connection conn = connect(params, false);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            for (int i = 0; i < columns.size(); i++) {
                JdbcJson.bind(stmt, i + 1, data.get(columns.get(i)));
            }
            return stmt.executeUpdate();
        }
    }

    long updateRecord(ConnectionParams params, String table, String pkCol, JsonNode pkVal, String colName, JsonNode newVal, String schema, long maxBlobSize) throws SQLException {
        String sql = "UPDATE " + DamengSql.qualifiedName(table, schema)
                + " SET " + DamengSql.quoteIdentifier(colName) + " = ?"
                + " WHERE " + DamengSql.quoteIdentifier(pkCol) + " = ?";
        try (Connection conn = connect(params, false);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            JdbcJson.bind(stmt, 1, newVal);
            JdbcJson.bind(stmt, 2, pkVal);
            return stmt.executeUpdate();
        }
    }

    long deleteRecord(ConnectionParams params, String table, String pkCol, JsonNode pkVal, String schema) throws SQLException {
        String sql = "DELETE FROM " + DamengSql.qualifiedName(table, schema)
                + " WHERE " + DamengSql.quoteIdentifier(pkCol) + " = ?";
        try (Connection conn = connect(params, false);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            JdbcJson.bind(stmt, 1, pkVal);
            return stmt.executeUpdate();
        }
    }

    void createView(ConnectionParams params, String viewName, String definition, String schema) throws SQLException {
        executeDdl(params, schema, "CREATE OR REPLACE VIEW " + DamengSql.qualifiedName(viewName, schema) + " AS\n" + definition);
    }

    void alterView(ConnectionParams params, String viewName, String definition, String schema) throws SQLException {
        createView(params, viewName, definition, schema);
    }

    void dropView(ConnectionParams params, String viewName, String schema) throws SQLException {
        executeDdl(params, schema, "DROP VIEW " + DamengSql.qualifiedName(viewName, schema));
    }

    void dropIndex(ConnectionParams params, String table, String indexName, String schema) throws SQLException {
        executeDdl(params, schema, "DROP INDEX " + DamengSql.quoteIdentifier(indexName));
    }

    void dropForeignKey(ConnectionParams params, String table, String fkName, String schema) throws SQLException {
        executeDdl(params, schema, "ALTER TABLE " + DamengSql.qualifiedName(table, schema)
                + " DROP CONSTRAINT " + DamengSql.quoteIdentifier(fkName));
    }

    void createTrigger(ConnectionParams params, String triggerSql, String schema) throws SQLException {
        executeDdl(params, schema, triggerSql);
    }

    void dropTrigger(ConnectionParams params, String triggerName, String tableName, String schema) throws SQLException {
        executeDdl(params, schema, "DROP TRIGGER " + DamengSql.qualifiedName(triggerName, schema));
    }

    ObjectNode explainQuery(ConnectionParams params, String query, boolean analyze, String schema) throws SQLException {
        SqlSafety.requireReadOnly(query);
        String explainSql = explainSql(query, analyze);
        try (Connection conn = connect(params)) {
            // DM materializes EXPLAIN FOR internally and rejects it when the JDBC connection is marked read-only.
            conn.setReadOnly(false);
            applySchema(conn, schema);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(settings().queryTimeoutSec());
                try (ResultSet rs = stmt.executeQuery(explainSql)) {
                    return readExplainPlan(rs, query);
                } catch (SQLException e) {
                    if (analyze) {
                        try (ResultSet rs = stmt.executeQuery(explainSql(query, false))) {
                            return readExplainPlan(rs, query);
                        }
                    }
                    throw e;
                }
            }
        }
    }

    private void executeDdl(ConnectionParams params, String schema, String sql) throws SQLException {
        try (Connection conn = connect(params, false);
             Statement stmt = conn.createStatement()) {
            applySchema(conn, schema);
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            stmt.execute(sql);
        }
    }

    private ObjectNode executeOne(Statement stmt, String query, Integer limit, int page) throws SQLException {
        SqlSafety.requireNotEmpty(query);
        boolean paginate = limit != null && SqlSafety.isReadOnlyQuery(query);
        String finalQuery = paginate ? QueryPaginator.paginated(query, limit, page) : query;
        boolean hasResultSet = stmt.execute(finalQuery);
        if (!hasResultSet) {
            int affectedRows = stmt.getUpdateCount();
            ObjectNode result = Json.NODES.objectNode();
            result.set("columns", Json.NODES.arrayNode());
            result.set("rows", Json.NODES.arrayNode());
            result.put("affected_rows", Math.max(affectedRows, 0));
            result.put("truncated", false);
            result.set("pagination", Json.NODES.nullNode());
            return result;
        }
        try (ResultSet rs = stmt.getResultSet()) {
            return ResultSetJson.toQueryResult(rs, paginate ? limit : null, page);
        }
    }

    private static double elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    private Connection connect(ConnectionParams params) throws SQLException {
        return connect(params, true);
    }

    private Connection connect(ConnectionParams params, boolean readOnly) throws SQLException {
        PluginSettings current = settings();
        Connection conn = driver.connect(params.jdbcUrl(), params.properties(current.connectTimeoutMs()));
        if (conn == null) {
            throw new SQLException("Dameng JDBC driver refused URL: " + params.jdbcUrl());
        }
        conn.setReadOnly(readOnly);
        return conn;
    }

    private void applySchema(Connection conn, String schema) throws SQLException {
        if (schema == null || schema.isBlank()) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            stmt.execute("SET SCHEMA " + DamengSql.quoteIdentifier(schema));
        }
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

    private static List<String> requestedTables(List<String> allTables, List<String> requestedTables) {
        if (requestedTables == null || requestedTables.isEmpty()) {
            return allTables;
        }

        Set<String> existing = new LinkedHashSet<>(allTables);
        List<String> tables = new ArrayList<>();
        for (String requested : requestedTables) {
            String table = normalizeIdentifier(requested);
            if (existing.contains(table)) {
                tables.add(table);
            }
        }
        return tables;
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
                    item.put("on_delete", nullableText(normalizeForeignKeyAction(rs.getString("DELETE_RULE"))));
                    item.put("on_update", Json.NODES.nullNode());
                    fks.add(item);
                }
                return fks;
            }
        }
    }

    private Map<String, String> getRoutineDefinitions(Connection conn, String owner) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("""
                SELECT NAME, TEXT
                FROM ALL_SOURCE
                WHERE OWNER = ?
                  AND TYPE IN ('FUNCTION', 'PROCEDURE')
                ORDER BY NAME, TYPE, LINE
                """)) {
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            stmt.setString(1, owner);
            try (ResultSet rs = stmt.executeQuery()) {
                Map<String, StringBuilder> builders = new LinkedHashMap<>();
                while (rs.next()) {
                    String name = rs.getString("NAME");
                    String text = textValue(rs, "TEXT");
                    if (name != null && text != null) {
                        builders.computeIfAbsent(name, ignored -> new StringBuilder()).append(text);
                    }
                }

                Map<String, String> definitions = new LinkedHashMap<>();
                builders.forEach((name, builder) -> {
                    String definition = builder.toString().strip();
                    if (!definition.isBlank()) {
                        definitions.put(name, definition);
                    }
                });
                return definitions;
            }
        } catch (SQLException e) {
            return Map.of();
        }
    }

    private List<Map<String, JsonNode>> getRoutineParameters(Connection conn, String owner, String routineName) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("""
                SELECT ARGUMENT_NAME, DATA_TYPE, IN_OUT, POSITION, SEQUENCE
                FROM ALL_ARGUMENTS
                WHERE OWNER = ?
                  AND OBJECT_NAME = ?
                  AND DATA_LEVEL = 0
                ORDER BY SEQUENCE
                """)) {
            stmt.setQueryTimeout(settings().queryTimeoutSec());
            stmt.setString(1, owner);
            stmt.setString(2, normalizeIdentifier(routineName));
            try (ResultSet rs = stmt.executeQuery()) {
                List<Map<String, JsonNode>> params = new ArrayList<>();
                while (rs.next()) {
                    Map<String, JsonNode> item = new LinkedHashMap<>();
                    String name = rs.getString("ARGUMENT_NAME");
                    String dataType = rs.getString("DATA_TYPE");
                    String mode = normalizeParameterMode(rs.getString("IN_OUT"));
                    int position = rs.getInt("POSITION");
                    int sequence = rs.getInt("SEQUENCE");
                    item.put("name", Json.NODES.textNode(name == null ? "" : name));
                    item.put("data_type", Json.NODES.textNode(dataType == null ? "" : dataType));
                    item.put("mode", Json.NODES.textNode(mode));
                    item.put("ordinal_position", Json.NODES.numberNode(position >= 0 ? position : sequence));
                    params.add(item);
                }
                return params;
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

    static String normalizeTriggerEvent(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.strip().toUpperCase(Locale.ROOT).replace('_', ' ');
    }

    static String normalizeTriggerTiming(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('_', ' ');
        if (normalized.contains("BEFORE")) {
            return "BEFORE";
        }
        if (normalized.contains("AFTER")) {
            return "AFTER";
        }
        if (normalized.contains("INSTEAD")) {
            return "INSTEAD OF";
        }
        return normalized;
    }

    static String triggerDefinition(String description, String body, String name, String tableName, String timing, String event) {
        String cleanDescription = description == null ? "" : description.strip();
        String cleanBody = body == null ? "" : body.strip();
        if (startsWithCreateTrigger(cleanBody)) {
            return cleanBody;
        }
        if (!cleanDescription.isBlank() && !cleanBody.isBlank()) {
            return cleanDescription + "\n" + cleanBody;
        }
        if (!cleanBody.isBlank()) {
            return triggerSignature(name, tableName, timing, event) + "\n" + cleanBody;
        }
        if (!cleanDescription.isBlank()) {
            return cleanDescription;
        }
        return triggerSignature(name, tableName, timing, event);
    }

    private static boolean startsWithCreateTrigger(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.stripLeading().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.startsWith("CREATE TRIGGER ") || normalized.startsWith("CREATE OR REPLACE TRIGGER ");
    }

    static String triggerSignature(String name, String tableName, String timing, String event) {
        StringBuilder signature = new StringBuilder("CREATE TRIGGER ").append(name);
        if (timing != null && !timing.isBlank()) {
            signature.append(' ').append(timing);
        }
        if (event != null && !event.isBlank()) {
            signature.append(' ').append(event);
        }
        if (tableName != null && !tableName.isBlank()) {
            signature.append(" ON ").append(tableName);
        }
        signature.append(" FOR EACH ROW");
        return signature.toString();
    }

    static String normalizeForeignKeyAction(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toUpperCase(Locale.ROOT).replace('_', ' ');
    }

    static String normalizeParameterMode(String value) {
        if (value == null || value.isBlank()) {
            return "IN";
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('/', ' ');
        if (normalized.contains("OUT") && normalized.contains("IN")) {
            return "INOUT";
        }
        if (normalized.contains("OUT")) {
            return "OUT";
        }
        return "IN";
    }

    static String routineSignature(String routineType, String routineName, List<Map<String, JsonNode>> parameters) {
        String type = normalizeRoutineType(routineType);
        List<String> args = new ArrayList<>();
        String returnType = null;
        for (Map<String, JsonNode> parameter : parameters) {
            String name = nodeText(parameter.get("name"));
            String dataType = nodeText(parameter.get("data_type"));
            String mode = normalizeParameterMode(nodeText(parameter.get("mode")));
            int ordinal = parameter.getOrDefault("ordinal_position", Json.NODES.numberNode(-1)).asInt(-1);
            if ("FUNCTION".equals(type) && ordinal == 0 && "OUT".equals(mode)) {
                returnType = dataType;
                continue;
            }
            StringBuilder arg = new StringBuilder();
            if (mode != null && !mode.isBlank()) {
                arg.append(mode).append(' ');
            }
            if (name != null && !name.isBlank()) {
                arg.append(name).append(' ');
            }
            if (dataType != null && !dataType.isBlank()) {
                arg.append(dataType);
            }
            String rendered = arg.toString().strip();
            if (!rendered.isBlank()) {
                args.add(rendered);
            }
        }

        StringBuilder signature = new StringBuilder(type).append(' ').append(routineName).append('(')
                .append(String.join(", ", args)).append(')');
        if ("FUNCTION".equals(type) && returnType != null && !returnType.isBlank()) {
            signature.append(" RETURN ").append(returnType);
        }
        return signature.toString();
    }

    private static String normalizeRoutineType(String value) {
        if (value == null || value.isBlank()) {
            return "PROCEDURE";
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return "FUNCTION".equals(normalized) ? "FUNCTION" : "PROCEDURE";
    }

    private static String textValue(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Clob clob) {
            long length = clob.length();
            if (length <= 0) {
                return "";
            }
            return clob.getSubString(1, Math.toIntExact(Math.min(length, Integer.MAX_VALUE)));
        }
        return rs.getString(column);
    }

    private static String explainSql(String query, boolean analyze) {
        String trimmed = query.strip();
        if (trimmed.regionMatches(true, 0, "EXPLAIN FOR", 0, "EXPLAIN FOR".length())) {
            return trimmed;
        }
        if (trimmed.regionMatches(true, 0, "EXPLAIN", 0, "EXPLAIN".length())) {
            trimmed = trimmed.substring("EXPLAIN".length()).strip();
        }
        if (analyze) {
            return "EXPLAIN ANALYZE FOR " + trimmed;
        }
        return "EXPLAIN FOR " + trimmed;
    }

    private static ObjectNode readExplainPlan(ResultSet rs, String originalQuery) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>();
        boolean hasPlanColumns = false;
        for (int i = 1; i <= columnCount; i++) {
            String column = meta.getColumnLabel(i);
            if (column == null || column.isBlank()) {
                column = meta.getColumnName(i);
            }
            column = column.toUpperCase(Locale.ROOT);
            columns.add(column);
            if ("OPERATION".equals(column) || "LEVEL_ID".equals(column)) {
                hasPlanColumns = true;
            }
        }

        if (!hasPlanColumns) {
            return ExplainParser.toExplainPlan(readExplainOutput(rs), originalQuery);
        }

        List<Map<String, String>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                row.put(columns.get(i - 1), value == null ? null : value.toString());
            }
            rows.add(row);
        }
        return ExplainParser.toExplainPlan(rows, null, originalQuery);
    }

    private static String readExplainOutput(ResultSet rs) throws SQLException {
        StringBuilder output = new StringBuilder();
        while (rs.next()) {
            if (!output.isEmpty()) {
                output.append(System.lineSeparator());
            }
            output.append(rs.getString(1));
        }
        return output.toString();
    }

    private static String nodeText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
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
