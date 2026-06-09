package dev.tabularis.dameng;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Base64;
import java.util.Locale;

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

    static void bind(PreparedStatement stmt, int index, JsonNode value, int sqlType, String typeName, long maxBlobSize) throws SQLException {
        if (value == null || value.isMissingNode() || value.isNull()) {
            stmt.setNull(index, sqlType == Types.OTHER ? Types.NULL : sqlType);
            return;
        }

        switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> stmt.setInt(index, value.asInt());
            case Types.BIGINT -> stmt.setLong(index, value.asLong());
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> stmt.setDouble(index, value.asDouble());
            case Types.NUMERIC, Types.DECIMAL -> stmt.setBigDecimal(index, decimal(value));
            case Types.BOOLEAN, Types.BIT -> stmt.setBoolean(index, booleanValue(value));
            case Types.DATE -> stmt.setDate(index, Date.valueOf(text(value)));
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> stmt.setTime(index, Time.valueOf(text(value)));
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> stmt.setTimestamp(index, Timestamp.valueOf(timestampText(value)));
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> bindBytes(stmt, index, value, maxBlobSize);
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> bindClob(stmt, index, value, maxBlobSize);
            default -> {
                if (isBinaryType(typeName)) {
                    bindBytes(stmt, index, value, maxBlobSize);
                } else if (isClobType(typeName)) {
                    bindClob(stmt, index, value, maxBlobSize);
                } else {
                    stmt.setString(index, stringValue(value));
                }
            }
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

    private static BigDecimal decimal(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue();
        }
        return new BigDecimal(text(value));
    }

    private static boolean booleanValue(JsonNode value) {
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        String text = text(value).strip().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "y".equals(text) || "yes".equals(text);
    }

    private static void bindBytes(PreparedStatement stmt, int index, JsonNode value, long maxBlobSize) throws SQLException {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Text(value));
        } catch (IllegalArgumentException e) {
            throw new RpcException(-32602, "Binary values must be base64 encoded.");
        }
        if (maxBlobSize > 0 && bytes.length > maxBlobSize) {
            throw new RpcException(-32602, "Binary value is " + bytes.length + " bytes, exceeding max_blob_size " + maxBlobSize + ".");
        }
        stmt.setBytes(index, bytes);
    }

    private static void bindClob(PreparedStatement stmt, int index, JsonNode value, long maxBlobSize) throws SQLException {
        String text = stringValue(value);
        if (maxBlobSize > 0 && text.length() > maxBlobSize) {
            throw new RpcException(-32602, "Text value is " + text.length() + " chars, exceeding max_blob_size " + maxBlobSize + ".");
        }
        stmt.setCharacterStream(index, new StringReader(text), text.length());
    }

    private static String stringValue(JsonNode value) {
        if (value.isObject() || value.isArray()) {
            return json(value);
        }
        if (value.isNumber()) {
            return value.decimalValue().stripTrailingZeros().toPlainString();
        }
        return value.asText();
    }

    private static String text(JsonNode value) {
        if (value.isObject() || value.isArray()) {
            return json(value);
        }
        return value.asText();
    }

    private static String timestampText(JsonNode value) {
        return text(value).replace('T', ' ');
    }

    private static String base64Text(JsonNode value) {
        String text = text(value).strip();
        int comma = text.indexOf(',');
        if (text.regionMatches(true, 0, "data:", 0, 5) && comma >= 0) {
            return text.substring(comma + 1);
        }
        return text;
    }

    private static boolean isBinaryType(String typeName) {
        if (typeName == null) {
            return false;
        }
        String normalized = typeName.toUpperCase(Locale.ROOT);
        return normalized.contains("BLOB")
                || normalized.contains("BINARY")
                || normalized.equals("IMAGE")
                || normalized.equals("BFILE");
    }

    private static boolean isClobType(String typeName) {
        if (typeName == null) {
            return false;
        }
        String normalized = typeName.toUpperCase(Locale.ROOT);
        return normalized.contains("CLOB") || normalized.contains("LONGVARCHAR");
    }
}
