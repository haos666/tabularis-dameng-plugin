package dev.tabularis.dameng;

import java.util.Locale;
import java.util.Set;

final class SqlSafety {
    private static final Set<String> READONLY_STARTERS = Set.of("SELECT", "WITH", "EXPLAIN");
    private static final Set<String> BLOCKED_STARTERS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "TRUNCATE",
            "CALL", "EXEC", "EXECUTE", "GRANT", "REVOKE", "COMMENT", "RENAME", "ANALYZE",
            "BEGIN", "DECLARE", "LOCK", "COMMIT", "ROLLBACK"
    );

    private SqlSafety() {
    }

    static void requireReadOnly(String sql) {
        String first = firstToken(sql);
        if (first == null) {
            throw new RpcException(-32602, "Query is empty.");
        }
        if (BLOCKED_STARTERS.contains(first) || !READONLY_STARTERS.contains(first)) {
            throw new RpcException(-32603, "Dameng plugin is read-only; only SELECT/WITH/EXPLAIN queries are allowed.");
        }
    }

    static String firstToken(String sql) {
        if (sql == null) {
            return null;
        }
        String cleaned = stripLeadingComments(sql).stripLeading();
        if (cleaned.isEmpty()) {
            return null;
        }
        int end = 0;
        while (end < cleaned.length() && Character.isLetter(cleaned.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        return cleaned.substring(0, end).toUpperCase(Locale.ROOT);
    }

    private static String stripLeadingComments(String sql) {
        String s = sql;
        boolean changed;
        do {
            changed = false;
            s = s.stripLeading();
            if (s.startsWith("--")) {
                int newline = s.indexOf('\n');
                s = newline >= 0 ? s.substring(newline + 1) : "";
                changed = true;
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                s = end >= 0 ? s.substring(end + 2) : "";
                changed = true;
            }
        } while (changed);
        return s;
    }
}
