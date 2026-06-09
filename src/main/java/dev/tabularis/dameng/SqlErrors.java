package dev.tabularis.dameng;

import java.sql.SQLException;

final class SqlErrors {
    private SqlErrors() {
    }

    static String message(String context, SQLException error) {
        StringBuilder message = new StringBuilder();
        if (context != null && !context.isBlank()) {
            message.append(context.strip()).append(": ");
        }
        message.append(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        if (error.getSQLState() != null && !error.getSQLState().isBlank()) {
            message.append(" [SQLState ").append(error.getSQLState()).append(']');
        }
        if (error.getErrorCode() != 0) {
            message.append(" [DM code ").append(error.getErrorCode()).append(']');
        }
        return message.toString();
    }

    static SQLException withContext(String context, SQLException error) {
        return new SQLException(message(context, error), error.getSQLState(), error.getErrorCode(), error);
    }
}
