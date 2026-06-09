package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DamengSqlTest {
    @Test
    void quotesOrdinaryAndSchemaQualifiedIdentifiers() {
        assertEquals("\"ORDERS\"", DamengSql.quoteIdentifier("orders"));
        assertEquals("\"MixedCase\"", DamengSql.quoteIdentifier("\"MixedCase\""));
        assertEquals("\"DEV2\".\"ORDERS\"", DamengSql.qualifiedName("orders", "dev2"));
        assertEquals("\"DEV2\".\"ORDERS\"", DamengSql.qualifiedName("dev2.orders", null));
    }

    @Test
    void createsTableWithIdentityAndPrimaryKey() {
        var sql = DamengSql.createTableSql("t_write_test", List.of(
                new ColumnDefinition("id", "INT", false, true, true, null, "Primary identifier"),
                new ColumnDefinition("name", "VARCHAR(80)", true, false, false, "'unknown'", "Display name")
        ), "dev2", "Editable rows");

        assertEquals("""
                CREATE TABLE "DEV2"."T_WRITE_TEST" (
                  "ID" INT IDENTITY(1,1) NOT NULL,
                  "NAME" VARCHAR(80) DEFAULT 'unknown',
                  PRIMARY KEY ("ID")
                )""", sql.get(0));
        assertEquals("COMMENT ON TABLE \"DEV2\".\"T_WRITE_TEST\" IS 'Editable rows'", sql.get(1));
        assertEquals("COMMENT ON COLUMN \"DEV2\".\"T_WRITE_TEST\".\"ID\" IS 'Primary identifier'", sql.get(2));
        assertEquals("COMMENT ON COLUMN \"DEV2\".\"T_WRITE_TEST\".\"NAME\" IS 'Display name'", sql.get(3));
    }

    @Test
    void altersColumnWithRenameAndModifyStatements() {
        var statements = DamengSql.alterColumnSql(
                "orders",
                new ColumnDefinition("name", "VARCHAR(40)", true, false, false, null, null),
                new ColumnDefinition("display_name", "VARCHAR(80)", false, false, false, "'n/a'", null),
                "dev2"
        );

        assertEquals("ALTER TABLE \"DEV2\".\"ORDERS\" RENAME COLUMN \"NAME\" TO \"DISPLAY_NAME\"", statements.get(0));
        assertEquals("ALTER TABLE \"DEV2\".\"ORDERS\" MODIFY \"DISPLAY_NAME\" VARCHAR(80) NOT NULL DEFAULT 'n/a'", statements.get(1));
    }

    @Test
    void createsIndexAndForeignKeySql() {
        assertEquals(
                "CREATE UNIQUE INDEX \"UX_TEST\" ON \"DEV2\".\"ORDERS\" (\"ORDER_NO\")",
                DamengSql.createIndexSql("orders", "ux_test", List.of("order_no"), true, "dev2").get(0)
        );
        assertEquals(
                "ALTER TABLE \"DEV2\".\"ORDERS\" ADD CONSTRAINT \"FK_TEST\" FOREIGN KEY (\"CUSTOMER_ID\") REFERENCES \"DEV2\".\"CUSTOMERS\" (\"ID\") ON DELETE CASCADE",
                DamengSql.createForeignKeySql("orders", "fk_test", "customer_id", "customers", "id", "cascade", null, "dev2").get(0)
        );
    }

    @Test
    void rejectsPrimaryKeyAlteration() {
        assertThrows(RpcException.class, () -> DamengSql.alterColumnSql(
                "orders",
                new ColumnDefinition("id", "INT", false, false, false, null, null),
                new ColumnDefinition("id", "INT", false, true, false, null, null),
                null
        ));
    }

    @Test
    void addsColumnCommentStatement() {
        var statements = DamengSql.addColumnSql(
                "orders",
                new ColumnDefinition("memo", "VARCHAR(200)", true, false, false, null, "Operator note"),
                "dev2"
        );

        assertEquals("ALTER TABLE \"DEV2\".\"ORDERS\" ADD \"MEMO\" VARCHAR(200)", statements.get(0));
        assertEquals("COMMENT ON COLUMN \"DEV2\".\"ORDERS\".\"MEMO\" IS 'Operator note'", statements.get(1));
    }
}
