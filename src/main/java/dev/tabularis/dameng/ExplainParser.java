package dev.tabularis.dameng;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExplainParser {
    private static final Pattern LINE_PATTERN = Pattern.compile("^(\\d+)\\s(\\s*)#(.+)$");
    private static final Pattern COST_PATTERN = Pattern.compile("\\[\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*]");
    private static final Pattern RELATION_PATTERN = Pattern.compile("\\(([^()]+)\\s+as\\s+([^()]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_PATTERN = Pattern.compile("\\bKEY\\(([^)]+)\\)");

    private ExplainParser() {
    }

    static ObjectNode toExplainPlan(String rawOutput, String originalQuery) {
        ObjectNode root = null;
        Deque<Frame> stack = new ArrayDeque<>();
        int generatedId = 1;

        for (String line : rawOutput.split("\\R")) {
            Matcher matcher = LINE_PATTERN.matcher(line);
            if (!matcher.find()) {
                continue;
            }

            int indent = matcher.group(2).length();
            ObjectNode node = parseNode("node-" + generatedId++, "#" + matcher.group(3).strip(), line);
            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                root = node;
            } else {
                ((ArrayNode) stack.peek().node.get("children")).add(node);
            }
            stack.push(new Frame(indent, node));
        }

        if (root == null) {
            root = baseNode("node-1", "EXPLAIN");
            root.put("filter", rawOutput == null ? "" : rawOutput.strip());
        }

        ObjectNode plan = Json.NODES.objectNode();
        plan.set("root", root);
        plan.set("planning_time_ms", Json.NODES.nullNode());
        plan.set("execution_time_ms", Json.NODES.nullNode());
        plan.put("original_query", originalQuery);
        plan.put("driver", "dameng");
        plan.put("has_analyze_data", false);
        plan.put("raw_output", rawOutput);
        return plan;
    }

    static ObjectNode toExplainPlan(List<Map<String, String>> rows, String rawOutput, String originalQuery) {
        ObjectNode root = null;
        Deque<LevelFrame> stack = new ArrayDeque<>();
        int generatedId = 1;

        for (Map<String, String> row : rows) {
            String operation = value(row, "OPERATION");
            if (operation == null || operation.isBlank()) {
                continue;
            }

            int level = intValue(value(row, "LEVEL_ID"), 1);
            String planId = value(row, "PLAN_ID");
            ObjectNode node = baseNode(planId == null ? "node-" + generatedId : "node-" + planId + "-" + generatedId, operation.strip());
            generatedId++;
            putText(node, "relation", value(row, "TAB_NAME"));
            putNumber(node, "total_cost", value(row, "COST"));
            putNumber(node, "plan_rows", value(row, "ROW_NUMS"));
            putText(node, "filter", value(row, "FILTER"));
            putText(node, "hash_condition", value(row, "JOIN_COND"));
            putExtra(node, "index_name", value(row, "IDX_NAME"));
            putExtra(node, "scan_type", value(row, "SCAN_TYPE"));
            putExtra(node, "scan_range", value(row, "SCAN_RANGE"));
            putExtra(node, "bytes", value(row, "BYTES"));
            putExtra(node, "cpu_cost", value(row, "CPU_COST"));
            putExtra(node, "io_cost", value(row, "IO_COST"));
            putExtra(node, "advice_info", value(row, "ADVICE_INFO"));
            putExtra(node, "partition_start", value(row, "PSTART"));
            putExtra(node, "partition_stop", value(row, "PSTOP"));

            while (!stack.isEmpty() && stack.peek().level >= level) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                root = node;
            } else {
                ((ArrayNode) stack.peek().node.get("children")).add(node);
            }
            stack.push(new LevelFrame(level, node));
        }

        if (root == null) {
            root = baseNode("node-1", "EXPLAIN");
            root.put("filter", rawOutput == null ? "" : rawOutput.strip());
        }

        ObjectNode plan = Json.NODES.objectNode();
        plan.set("root", root);
        plan.set("planning_time_ms", Json.NODES.nullNode());
        plan.set("execution_time_ms", Json.NODES.nullNode());
        plan.put("original_query", originalQuery);
        plan.put("driver", "dameng");
        plan.put("has_analyze_data", false);
        plan.put("raw_output", rawOutput == null || rawOutput.isBlank() ? rawRows(rows) : rawOutput);
        return plan;
    }

    private static ObjectNode parseNode(String id, String body, String originalLine) {
        ObjectNode node = baseNode(id, nodeType(body));
        node.put("filter", details(body));

        Matcher costs = COST_PATTERN.matcher(body);
        if (costs.find()) {
            node.put("startup_cost", number(costs.group(1)));
            node.put("plan_rows", number(costs.group(2)));
            node.put("total_cost", number(costs.group(3)));
        }

        Matcher relation = RELATION_PATTERN.matcher(body);
        if (relation.find()) {
            node.put("relation", relation.group(1).strip());
            node.withObject("/extra").put("alias", relation.group(2).strip());
        }

        Matcher key = KEY_PATTERN.matcher(body);
        if (key.find()) {
            node.put("hash_condition", key.group(1).strip());
        }

        node.withObject("/extra").put("raw_line", originalLine.strip());
        return node;
    }

    private static ObjectNode baseNode(String id, String nodeType) {
        ObjectNode node = Json.NODES.objectNode();
        node.put("id", id);
        node.put("node_type", nodeType);
        node.set("relation", Json.NODES.nullNode());
        node.set("startup_cost", Json.NODES.nullNode());
        node.set("total_cost", Json.NODES.nullNode());
        node.set("plan_rows", Json.NODES.nullNode());
        node.set("actual_rows", Json.NODES.nullNode());
        node.set("actual_time_ms", Json.NODES.nullNode());
        node.set("actual_loops", Json.NODES.nullNode());
        node.set("buffers_hit", Json.NODES.nullNode());
        node.set("buffers_read", Json.NODES.nullNode());
        node.set("filter", Json.NODES.nullNode());
        node.set("index_condition", Json.NODES.nullNode());
        node.set("join_type", Json.NODES.nullNode());
        node.set("hash_condition", Json.NODES.nullNode());
        node.set("extra", Json.NODES.objectNode());
        node.set("children", Json.NODES.arrayNode());
        return node;
    }

    private static String nodeType(String body) {
        String beforeDetails = body.split(";", 2)[0];
        String beforeCost = beforeDetails.split("\\[", 2)[0];
        String beforeColon = beforeCost.split(":", 2)[0];
        return beforeColon.strip();
    }

    private static String details(String body) {
        int semicolon = body.indexOf(';');
        if (semicolon >= 0 && semicolon + 1 < body.length()) {
            return body.substring(semicolon + 1).strip();
        }
        int bracket = body.indexOf(']');
        if (bracket >= 0 && bracket + 1 < body.length()) {
            return body.substring(bracket + 1).strip();
        }
        return "";
    }

    private static double number(String value) {
        return Double.parseDouble(value);
    }

    private static String value(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return "NULL".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    private static int intValue(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value.strip());
        }
    }

    private static void putNumber(ObjectNode node, String field, String value) {
        if (value == null) {
            return;
        }
        try {
            node.put(field, Double.parseDouble(value.strip()));
        } catch (NumberFormatException ignored) {
            node.withObject("/extra").put(field, value.strip());
        }
    }

    private static void putExtra(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.withObject("/extra").put(field, value.strip());
        }
    }

    private static String rawRows(List<Map<String, String>> rows) {
        StringBuilder output = new StringBuilder();
        for (Map<String, String> row : rows) {
            if (!output.isEmpty()) {
                output.append(System.lineSeparator());
            }
            output.append(row);
        }
        return output.toString();
    }

    private record Frame(int indent, ObjectNode node) {
    }

    private record LevelFrame(int level, ObjectNode node) {
    }
}
