package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ResultSetJsonTest {
    @Test
    void rendersBinaryValuesAsTabularisBlobWireFormat() throws Exception {
        ResultSet rs = resultSet(
                new String[]{"BIN_COL", "BLOB_COL"},
                new int[]{Types.VARBINARY, Types.BLOB},
                new Object[]{"hi".getBytes(), blob("bye".getBytes())}
        );

        var result = ResultSetJson.toQueryResult(rs, null, 1);

        assertEquals("BIN_COL", result.path("columns").path(0).asText());
        assertEquals("BLOB:2:application/octet-stream:aGk=", result.path("rows").path(0).path(0).asText());
        assertEquals("BLOB:3:application/octet-stream:Ynll", result.path("rows").path(0).path(1).asText());
    }

    private static ResultSet resultSet(String[] names, int[] types, Object[] values) {
        final int[] cursor = {-1};
        final Object[] current = {null};
        ResultSetMetaData meta = (ResultSetMetaData) Proxy.newProxyInstance(
                ResultSetMetaData.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> names.length;
                    case "getColumnLabel", "getColumnName" -> names[(int) args[0] - 1];
                    case "getColumnType" -> types[(int) args[0] - 1];
                    default -> defaultValue(method.getReturnType());
                }
        );
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++cursor[0] == 0;
                    case "getMetaData" -> meta;
                    case "getObject" -> {
                        current[0] = values[(int) args[0] - 1];
                        yield current[0];
                    }
                    case "wasNull" -> current[0] == null;
                    case "getBytes" -> values[(int) args[0] - 1];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Blob blob(byte[] bytes) {
        return (Blob) Proxy.newProxyInstance(
                Blob.class.getClassLoader(),
                new Class<?>[]{Blob.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "length" -> (long) bytes.length;
                    case "getBytes" -> Arrays.copyOfRange(bytes, (int) (long) args[0] - 1, (int) (long) args[0] - 1 + (int) args[1]);
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0.0;
        }
        return 0;
    }
}
