package dev.tabularis.dameng;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Properties;

final class ConnectionParams {
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    private ConnectionParams(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    static ConnectionParams from(JsonNode node) {
        String host = text(node.path("host"));
        if (host == null) {
            host = "localhost";
        }
        int port = node.path("port").isMissingNode() || node.path("port").isNull()
                ? 5236
                : node.path("port").asInt(5236);
        String username = text(node.path("username"));
        String password = text(node.path("password"));
        return new ConnectionParams(host, port, username, password);
    }

    String jdbcUrl() {
        return new StringBuilder("jdbc:dm://")
                .append(host)
                .append(':')
                .append(port)
                .toString();
    }

    Properties properties(int connectTimeoutMs) {
        Properties props = new Properties();
        if (username != null) {
            props.setProperty("user", username);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        props.setProperty("connectTimeout", Integer.toString(connectTimeoutMs));
        props.setProperty("loginTimeout", Integer.toString(Math.max(1, connectTimeoutMs / 1000)));
        return props;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }
}
