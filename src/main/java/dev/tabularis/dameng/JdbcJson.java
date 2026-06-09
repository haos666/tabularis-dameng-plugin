package dev.tabularis.dameng;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

final class JdbcJson {
    private JdbcJson() {
    }

    static void bind(PreparedStatement stmt, int index, JsonNode value) throws SQLException {
        if (value == null || value.isMissingNode() || value.isNull()) {
            stmt.setNull(index, Types.NULL);
        } else if (value.isIntegralNumber()) {
            stmt.setLong(index, value.longValue());
        } else if (value.isFloatingPointNumber() || value.isBigDecimal()) {
            stmt.setBigDecimal(index, value.decimalValue());
        } else if (value.isBoolean()) {
            stmt.setBoolean(index, value.booleanValue());
        } else if (value.isObject() || value.isArray()) {
            stmt.setString(index, json(value));
        } else {
            stmt.setString(index, value.asText());
        }
    }

    static String debugValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "NULL";
        }
        if (value.isNumber()) {
            BigDecimal decimal = value.decimalValue();
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value.isBoolean()) {
            return Boolean.toString(value.booleanValue());
        }
        if (value.isObject() || value.isArray()) {
            return json(value);
        }
        return value.asText();
    }

    private static String json(JsonNode value) {
        try {
            return Json.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RpcException(-32603, "Failed to serialize JSON value: " + e.getMessage());
        }
    }
}
