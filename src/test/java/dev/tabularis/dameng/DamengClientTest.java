package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DamengClientTest {
    @Test
    void normalizesUnquotedIdentifiersToUppercase() {
        assertEquals("DEV2", DamengClient.normalizeIdentifier("dev2"));
        assertEquals("ORDERS", DamengClient.normalizeIdentifier(" orders "));
    }

    @Test
    void preservesQuotedIdentifierCaseAndContent() {
        assertEquals("MixedCase", DamengClient.normalizeIdentifier("\"MixedCase\""));
    }

    @Test
    void normalizesForeignKeyActions() {
        assertEquals("NO ACTION", DamengClient.normalizeForeignKeyAction("no_action"));
        assertEquals("CASCADE", DamengClient.normalizeForeignKeyAction(" cascade "));
    }

    @Test
    void normalizesRoutineParameterModes() {
        assertEquals("IN", DamengClient.normalizeParameterMode(null));
        assertEquals("OUT", DamengClient.normalizeParameterMode("out"));
        assertEquals("INOUT", DamengClient.normalizeParameterMode("IN/OUT"));
    }

    @Test
    void buildsFunctionSignatureWithReturnParameter() {
        Map<String, com.fasterxml.jackson.databind.JsonNode> returnValue = new LinkedHashMap<>();
        returnValue.put("name", Json.NODES.textNode("V_RET"));
        returnValue.put("data_type", Json.NODES.textNode("DECIMAL"));
        returnValue.put("mode", Json.NODES.textNode("OUT"));
        returnValue.put("ordinal_position", Json.NODES.numberNode(0));

        Map<String, com.fasterxml.jackson.databind.JsonNode> customerId = new LinkedHashMap<>();
        customerId.put("name", Json.NODES.textNode("P_CUSTOMER_ID"));
        customerId.put("data_type", Json.NODES.textNode("INT"));
        customerId.put("mode", Json.NODES.textNode("IN"));
        customerId.put("ordinal_position", Json.NODES.numberNode(1));

        assertEquals(
                "FUNCTION FN_CUSTOMER_TOTAL_AMOUNT(IN P_CUSTOMER_ID INT) RETURN DECIMAL",
                DamengClient.routineSignature("FUNCTION", "FN_CUSTOMER_TOTAL_AMOUNT", List.of(returnValue, customerId))
        );
    }

    @Test
    void buildsProcedureSignatureWithoutParameters() {
        assertEquals(
                "PROCEDURE P_REFRESH_ORDER_STATS()",
                DamengClient.routineSignature("PROCEDURE", "P_REFRESH_ORDER_STATS", List.of())
        );
    }
}
