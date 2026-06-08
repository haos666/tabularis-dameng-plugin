package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExplainParserTest {
    @Test
    void parsesIndentedDamengExplainTree() {
        String raw = """
                1   #NSET2: [1, 1, 108]
                2     #PRJT2: [1, 1, 108]; exp_num(2), is_atom(FALSE)
                3       #HASH2 INNER JOIN: [1, 1, 108]; KEY(o.CUSTOMER_ID=c.ID)
                4         #BLKUP2: [1, 1, 56]; INDEX33555508(o)
                5           #SSEK2: [1, 1, 56]; scan_type(ASC), INDEX33555508(ORDERS as o), scan_range[1,1], is_global(0)
                """;

        var plan = ExplainParser.toExplainPlan(raw, "SELECT * FROM ORDERS");
        var root = plan.path("root");
        var project = root.path("children").path(0);
        var join = project.path("children").path(0);
        var seek = join.path("children").path(0).path("children").path(0);

        assertEquals("dameng", plan.path("driver").asText());
        assertFalse(plan.path("has_analyze_data").asBoolean());
        assertEquals("#NSET2", root.path("node_type").asText());
        assertEquals("#HASH2 INNER JOIN", join.path("node_type").asText());
        assertEquals(108.0, join.path("total_cost").asDouble());
        assertEquals("o.CUSTOMER_ID=c.ID", join.path("hash_condition").asText());
        assertEquals("ORDERS", seek.path("relation").asText());
        assertEquals("o", seek.path("extra").path("alias").asText());
        assertTrue(plan.path("raw_output").asText().contains("#SSEK2"));
    }

    @Test
    void parsesJdbcExplainForRows() {
        var plan = ExplainParser.toExplainPlan(List.of(
                Map.of("PLAN_ID", "1", "LEVEL_ID", "1", "OPERATION", "NSET2", "ROW_NUMS", "3", "COST", "42"),
                Map.of("PLAN_ID", "2", "LEVEL_ID", "2", "OPERATION", "PRJT2", "ROW_NUMS", "3", "COST", "42"),
                Map.of(
                        "PLAN_ID", "3",
                        "LEVEL_ID", "3",
                        "OPERATION", "HASH2 INNER JOIN",
                        "TAB_NAME", "ORDERS",
                        "IDX_NAME", "IDX_ORDERS_CUSTOMER",
                        "ROW_NUMS", "3",
                        "COST", "42",
                        "JOIN_COND", "O.CUSTOMER_ID=C.ID"
                )
        ), null, "SELECT * FROM ORDERS");

        var root = plan.path("root");
        var join = root.path("children").path(0).path("children").path(0);

        assertEquals("NSET2", root.path("node_type").asText());
        assertEquals(42.0, root.path("total_cost").asDouble());
        assertEquals("HASH2 INNER JOIN", join.path("node_type").asText());
        assertEquals("ORDERS", join.path("relation").asText());
        assertEquals("IDX_ORDERS_CUSTOMER", join.path("extra").path("index_name").asText());
        assertEquals("O.CUSTOMER_ID=C.ID", join.path("hash_condition").asText());
        assertTrue(plan.path("raw_output").asText().contains("OPERATION=HASH2 INNER JOIN"));
    }
}
