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
    void rejectsWriteStatements() {
        RpcException error = assertThrows(RpcException.class, () -> SqlSafety.requireReadOnly("delete from users"));

        assertEquals(-32603, error.code());
    }

    @Test
    void acceptsReadOnlyStatements() {
        SqlSafety.requireReadOnly("select * from users");
        SqlSafety.requireReadOnly("explain select * from users");
    }
}
