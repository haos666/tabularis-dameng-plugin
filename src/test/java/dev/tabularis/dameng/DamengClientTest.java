package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

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
}
