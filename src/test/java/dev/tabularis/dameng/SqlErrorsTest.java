package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SqlErrorsTest {
    @Test
    void includesContextSqlStateAndVendorCode() {
        SQLException error = new SQLException("syntax error", "42000", -2007);

        assertEquals(
                "execute_query: syntax error [SQLState 42000] [DM code -2007]",
                SqlErrors.message("execute_query", error)
        );
    }
}
