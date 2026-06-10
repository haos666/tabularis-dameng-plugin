package dev.tabularis.dameng;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.format.DateTimeFormatter;

final class ResultSetJson {
    private ResultSetJson() {
    }

    static ObjectNode toQueryResult(ResultSet rs, Integer limit, int page) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        int pageSize = limit == null ? 0 : Math.max(1, limit);
        int start = limit == null ? 0 : Math.max(0, (Math.max(1, page) - 1) * pageSize);
        int keep = limit == null ? Integer.MAX_VALUE : pageSize;

        ArrayNode columns = Json.NODES.arrayNode();
        for (int i = 1; i <= columnCount; i++) {
            String label = meta.getColumnLabel(i);
            columns.add(label == null || label.isBlank() ? meta.getColumnName(i) : label);
        }

        ArrayNode rows = Json.NODES.arrayNode();
        boolean truncated = false;
        int rowIndex = 0;
        while (rs.next()) {
            if (rowIndex++ < start) {
                continue;
            }
            if (rows.size() >= keep) {
                truncated = true;
                break;
            }
            ArrayNode row = Json.NODES.arrayNode();
            for (int i = 1; i <= columnCount; i++) {
                row.add(value(rs, meta, i));
            }
            rows.add(row);
        }

        ObjectNode result = Json.NODES.objectNode();
        result.set("columns", columns);
        result.set("rows", rows);
        result.put("affected_rows", 0);
        result.put("truncated", truncated);
        if (limit == null) {
            result.set("pagination", Json.NODES.nullNode());
        } else {
            ObjectNode pagination = Json.NODES.objectNode();
            pagination.put("page", Math.max(1, page));
            pagination.put("page_size", pageSize);
            pagination.set("total_rows", Json.NODES.nullNode());
            pagination.put("has_more", truncated);
            result.set("pagination", pagination);
        }
        return result;
    }

    private static JsonNode value(ResultSet rs, ResultSetMetaData meta, int index) throws SQLException {
        Object raw = rs.getObject(index);
        if (raw == null || rs.wasNull()) {
            return Json.NODES.nullNode();
        }

        int type = meta.getColumnType(index);
        return switch (type) {
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Json.NODES.numberNode(((Number) raw).intValue());
            case Types.BIGINT -> Json.NODES.numberNode(((Number) raw).longValue());
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> Json.NODES.numberNode(((Number) raw).doubleValue());
            case Types.NUMERIC, Types.DECIMAL -> decimal((BigDecimal) raw);
            case Types.BOOLEAN, Types.BIT -> Json.NODES.booleanNode(Boolean.parseBoolean(raw.toString()));
            case Types.DATE -> Json.NODES.textNode(((Date) raw).toLocalDate().toString());
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> Json.NODES.textNode(timeText(raw));
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> Json.NODES.textNode(timestampText(raw));
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> Json.NODES.textNode(BlobWire.encode(rs.getBytes(index)));
            case Types.BLOB -> Json.NODES.textNode(blobText((Blob) raw));
            case Types.CLOB, Types.NCLOB -> Json.NODES.textNode(clobText((Clob) raw));
            default -> Json.NODES.textNode(raw.toString());
        };
    }

    private static JsonNode decimal(BigDecimal value) {
        try {
            return Json.NODES.numberNode(value);
        } catch (NumberFormatException ignored) {
            return Json.NODES.textNode(value.toPlainString());
        }
    }

    private static String timeText(Object raw) {
        if (raw instanceof Time time) {
            return time.toLocalTime().toString();
        }
        return raw.toString();
    }

    private static String timestampText(Object raw) {
        if (raw instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        return raw.toString();
    }

    private static String blobText(Blob blob) throws SQLException {
        long length = blob.length();
        if (length > Integer.MAX_VALUE) {
            return "<BLOB " + length + " bytes>";
        }
        return BlobWire.encode(blob.getBytes(1, (int) length));
    }

    private static String clobText(Clob clob) throws SQLException {
        long length = clob.length();
        if (length > Integer.MAX_VALUE) {
            return "<CLOB " + length + " chars>";
        }
        return clob.getSubString(1, (int) length);
    }
}
