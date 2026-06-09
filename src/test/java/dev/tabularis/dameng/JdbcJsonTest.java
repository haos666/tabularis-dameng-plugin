package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcJsonTest {
    @Test
    void rendersDebugValuesForJdbcBindingCases() throws Exception {
        assertEquals("NULL", JdbcJson.debugValue(Json.MAPPER.readTree("null")));
        assertEquals("42", JdbcJson.debugValue(Json.MAPPER.readTree("42")));
        assertEquals("true", JdbcJson.debugValue(Json.MAPPER.readTree("true")));
        assertEquals("hello", JdbcJson.debugValue(Json.MAPPER.readTree("\"hello\"")));
        assertEquals("{\"a\":1}", JdbcJson.debugValue(Json.MAPPER.readTree("{\"a\":1}")));
        assertEquals("[1,2]", JdbcJson.debugValue(Json.MAPPER.readTree("[1,2]")));
    }

    @Test
    void bindsValuesUsingJdbcColumnTypes() throws Exception {
        Recorder recorder = new Recorder();
        PreparedStatement stmt = recorder.statement();

        JdbcJson.bind(stmt, 1, Json.MAPPER.readTree("42"), Types.INTEGER, "INT", 0);
        JdbcJson.bind(stmt, 2, Json.MAPPER.readTree("12.34"), Types.DECIMAL, "DECIMAL", 0);
        JdbcJson.bind(stmt, 3, Json.MAPPER.readTree("true"), Types.BOOLEAN, "BOOLEAN", 0);
        JdbcJson.bind(stmt, 4, Json.MAPPER.readTree("\"2026-06-09\""), Types.DATE, "DATE", 0);
        JdbcJson.bind(stmt, 5, Json.MAPPER.readTree("{\"a\":1}"), Types.CLOB, "CLOB", 100);
        JdbcJson.bind(stmt, 6, Json.MAPPER.readTree("\"aGk=\""), Types.BLOB, "BLOB", 10);

        assertEquals("setInt", recorder.calls.get(0).name());
        assertEquals(42, recorder.calls.get(0).args()[1]);
        assertEquals("setBigDecimal", recorder.calls.get(1).name());
        assertEquals("setBoolean", recorder.calls.get(2).name());
        assertEquals("setDate", recorder.calls.get(3).name());
        assertEquals("setCharacterStream", recorder.calls.get(4).name());
        assertEquals(7, recorder.calls.get(4).args()[2]);
        assertTrue(recorder.calls.get(4).args()[1] instanceof Reader);
        assertEquals("setBytes", recorder.calls.get(5).name());
        assertArrayEquals("hi".getBytes(), (byte[]) recorder.calls.get(5).args()[1]);
    }

    @Test
    void bindsDataUriBase64AndRejectsOversizedLobs() throws Exception {
        Recorder recorder = new Recorder();
        PreparedStatement stmt = recorder.statement();

        JdbcJson.bind(stmt, 1, Json.MAPPER.readTree("\"data:application/octet-stream;base64,aGk=\""), Types.VARBINARY, "VARBINARY", 2);

        assertArrayEquals("hi".getBytes(), (byte[]) recorder.calls.get(0).args()[1]);
        assertThrows(RpcException.class, () ->
                JdbcJson.bind(stmt, 2, Json.MAPPER.readTree("\"aGk=\""), Types.BLOB, "BLOB", 1)
        );
        assertThrows(RpcException.class, () ->
                JdbcJson.bind(stmt, 3, Json.MAPPER.readTree("\"not-base64\""), Types.BLOB, "BLOB", 0)
        );
        assertThrows(RpcException.class, () ->
                JdbcJson.bind(stmt, 4, Json.MAPPER.readTree("\"too long\""), Types.CLOB, "CLOB", 3)
        );
    }

    private static final class Recorder {
        private final List<Call> calls = new ArrayList<>();

        PreparedStatement statement() {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        calls.add(new Call(method.getName(), args == null ? new Object[0] : Arrays.copyOf(args, args.length)));
                        return switch (method.getReturnType().getName()) {
                            case "boolean" -> false;
                            case "int" -> 0;
                            case "long" -> 0L;
                            case "double" -> 0.0;
                            default -> null;
                        };
                    }
            );
        }
    }

    private record Call(String name, Object[] args) {
    }
}
