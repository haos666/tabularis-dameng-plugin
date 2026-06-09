package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
