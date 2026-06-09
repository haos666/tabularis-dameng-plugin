package dev.tabularis.dameng;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DamengSql {
    private DamengSql() {
    }

    static String quoteIdentifier(String value) {
        String normalized = DamengClient.normalizeIdentifier(value);
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    static String qualifiedName(String objectName, String schema) {
        List<String> parts = splitQualifiedName(objectName);
        if (parts.size() > 1) {
            return parts.stream().map(DamengSql::quoteIdentifier).reduce((left, right) -> left + "." + right).orElseThrow();
        }
        if (schema != null && !schema.isBlank()) {
            return quoteIdentifier(schema) + "." + quoteIdentifier(objectName);
        }
        return quoteIdentifier(objectName);
    }

    static String columnDefinition(ColumnDefinition column, boolean includePrimaryKey) {
        StringBuilder sql = new StringBuilder()
                .append(quoteIdentifier(column.name()))
                .append(' ')
                .append(autoIncrementType(column));
        if (!column.nullable() || column.primaryKey()) {
            sql.append(" NOT NULL");
        }
        if (column.defaultValue() != null && !column.defaultValue().isBlank() && !column.autoIncrement()) {
            sql.append(" DEFAULT ").append(column.defaultValue());
        }
        if (includePrimaryKey && column.primaryKey()) {
            sql.append(" PRIMARY KEY");
        }
        return sql.toString();
    }

    static List<String> createTableSql(String tableName, List<ColumnDefinition> columns, String schema, String tableComment) {
        if (columns.isEmpty()) {
            throw new RpcException(-32602, "At least one column is required.");
        }

        List<String> definitions = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        for (ColumnDefinition column : columns) {
            definitions.add(columnDefinition(column, false));
            if (column.primaryKey()) {
                primaryKeys.add(quoteIdentifier(column.name()));
            }
        }
        if (!primaryKeys.isEmpty()) {
            definitions.add("PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
        }
        List<String> statements = new ArrayList<>();
        String qualifiedTable = qualifiedName(tableName, schema);
        statements.add("CREATE TABLE " + qualifiedTable + " (\n  "
                + String.join(",\n  ", definitions) + "\n)");
        appendTableComment(statements, qualifiedTable, tableComment);
        appendColumnComments(statements, qualifiedTable, columns);
        return statements;
    }

    static List<String> addColumnSql(String table, ColumnDefinition column, String schema) {
        String qualifiedTable = qualifiedName(table, schema);
        List<String> statements = new ArrayList<>();
        statements.add("ALTER TABLE " + qualifiedTable
                + " ADD " + columnDefinition(column, column.primaryKey()));
        appendColumnComment(statements, qualifiedTable, column);
        return statements;
    }

    static String defaultInsertSql(String table, String schema) {
        return "INSERT INTO " + qualifiedName(table, schema) + " DEFAULT VALUES";
    }

    static List<String> alterColumnSql(String table, ColumnDefinition oldColumn, ColumnDefinition newColumn, String schema) {
        String tableName = qualifiedName(table, schema);
        List<String> statements = new ArrayList<>();
        if (!DamengClient.normalizeIdentifier(oldColumn.name()).equals(DamengClient.normalizeIdentifier(newColumn.name()))) {
            statements.add("ALTER TABLE " + tableName
                    + " RENAME COLUMN " + quoteIdentifier(oldColumn.name())
                    + " TO " + quoteIdentifier(newColumn.name()));
        }
        if (!sameDataType(oldColumn.dataType(), newColumn.dataType())
                || oldColumn.nullable() != newColumn.nullable()
                || oldColumn.autoIncrement() != newColumn.autoIncrement()
                || !sameDefault(oldColumn.defaultValue(), newColumn.defaultValue())) {
            statements.add("ALTER TABLE " + tableName + " MODIFY " + columnDefinition(newColumn, false));
        }
        if (oldColumn.primaryKey() != newColumn.primaryKey()) {
            throw new RpcException(-32601, "Altering primary keys is not supported by the DM plugin.");
        }
        if (!sameComment(oldColumn.comment(), newColumn.comment())) {
            appendColumnCommentValue(statements, tableName, newColumn.name(), newColumn.comment());
        }
        if (statements.isEmpty()) {
            throw new RpcException(-32602, "No column changes detected.");
        }
        return statements;
    }

    static List<String> createIndexSql(String table, String indexName, List<String> columns, boolean unique, String schema) {
        if (columns.isEmpty()) {
            throw new RpcException(-32602, "At least one index column is required.");
        }
        String prefix = unique ? "CREATE UNIQUE INDEX " : "CREATE INDEX ";
        return List.of(prefix + quoteIdentifier(indexName)
                + " ON " + qualifiedName(table, schema)
                + " (" + columns.stream().map(DamengSql::quoteIdentifier).reduce((a, b) -> a + ", " + b).orElse("") + ")");
    }

    static List<String> createForeignKeySql(
            String table,
            String fkName,
            String column,
            String refTable,
            String refColumn,
            String onDelete,
            String onUpdate,
            String schema
    ) {
        StringBuilder sql = new StringBuilder()
                .append("ALTER TABLE ").append(qualifiedName(table, schema))
                .append(" ADD CONSTRAINT ").append(quoteIdentifier(fkName))
                .append(" FOREIGN KEY (").append(quoteIdentifier(column)).append(")")
                .append(" REFERENCES ").append(qualifiedName(refTable, schema))
                .append(" (").append(quoteIdentifier(refColumn)).append(")");
        appendFkAction(sql, " ON DELETE ", onDelete);
        if (onUpdate != null && !onUpdate.isBlank()) {
            sql.append(" /* ON UPDATE ").append(onUpdate.strip().toUpperCase(Locale.ROOT)).append(" not supported by DM */");
        }
        return List.of(sql.toString());
    }

    static ArrayNode toArray(List<String> statements) {
        ArrayNode array = Json.NODES.arrayNode();
        statements.forEach(array::add);
        return array;
    }

    static List<String> columns(JsonNode node) {
        List<String> columns = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = text(item);
                if (value != null) {
                    columns.add(value);
                }
            }
        }
        return columns;
    }

    static List<ColumnDefinition> columnDefinitions(JsonNode node) {
        List<ColumnDefinition> columns = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                columns.add(ColumnDefinition.from(item));
            }
        }
        return columns;
    }

    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String autoIncrementType(ColumnDefinition column) {
        if (!column.autoIncrement()) {
            return column.dataType();
        }
        return column.dataType() + " IDENTITY(1,1)";
    }

    private static boolean sameDefault(String left, String right) {
        String l = left == null ? "" : left.strip();
        String r = right == null ? "" : right.strip();
        return l.equals(r);
    }

    private static boolean sameDataType(String left, String right) {
        String l = left == null ? "" : left.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        String r = right == null ? "" : right.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return l.equals(r);
    }

    private static boolean sameComment(String left, String right) {
        String l = left == null ? "" : left.strip();
        String r = right == null ? "" : right.strip();
        return l.equals(r);
    }

    private static void appendFkAction(StringBuilder sql, String keyword, String action) {
        if (action == null || action.isBlank()) {
            return;
        }
        String normalized = action.strip().toUpperCase(Locale.ROOT);
        if (!"NO ACTION".equals(normalized)) {
            sql.append(keyword).append(normalized);
        }
    }

    private static void appendTableComment(List<String> statements, String qualifiedTable, String comment) {
        if (comment != null && !comment.isBlank()) {
            statements.add("COMMENT ON TABLE " + qualifiedTable + " IS " + sqlString(comment));
        }
    }

    private static void appendColumnComments(List<String> statements, String qualifiedTable, List<ColumnDefinition> columns) {
        for (ColumnDefinition column : columns) {
            appendColumnComment(statements, qualifiedTable, column);
        }
    }

    private static void appendColumnComment(List<String> statements, String qualifiedTable, ColumnDefinition column) {
        if (column.comment() != null && !column.comment().isBlank()) {
            appendColumnCommentValue(statements, qualifiedTable, column.name(), column.comment());
        }
    }

    private static void appendColumnCommentValue(List<String> statements, String qualifiedTable, String columnName, String comment) {
        statements.add("COMMENT ON COLUMN " + qualifiedTable + "." + quoteIdentifier(columnName)
                + " IS " + sqlString(comment == null ? "" : comment));
    }

    private static String sqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static List<String> splitQualifiedName(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (c == '.' && !quoted) {
                parts.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString().strip());
        return parts.stream().filter(part -> !part.isBlank()).toList();
    }
}
