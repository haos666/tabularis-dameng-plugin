package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SqlSafetyTest {
    @Test
    void detectsFirstTokenAfterComments() {
        assertEquals("SELECT", SqlSafety.firstToken(" -- hello\n /* block */ select 1"));
        assertEquals("WITH", SqlSafety.firstToken("with x as (select 1) select * from x"));
    }

    @Test
    void rejectsWriteStatementsForExplainPath() {
        RpcException error = assertThrows(RpcException.class, () -> SqlSafety.requireReadOnly("delete from users"));

        assertEquals(-32603, error.code());
    }

    @Test
    void acceptsReadOnlyStatements() {
        SqlSafety.requireReadOnly("select * from users");
        SqlSafety.requireReadOnly("explain select * from users");
    }

    @Test
    void detectsReadOnlyQueriesForPagination() {
        assertEquals(true, SqlSafety.isReadOnlyQuery("select * from users"));
        assertEquals(false, SqlSafety.isReadOnlyQuery("update users set name = 'x'"));
    }

    @Test
    void rejectsEmptyQueriesOnly() {
        RpcException error = assertThrows(RpcException.class, () -> SqlSafety.requireNotEmpty(" -- comment"));

        assertEquals(-32602, error.code());
        SqlSafety.requireNotEmpty("delete from users");
    }
}
