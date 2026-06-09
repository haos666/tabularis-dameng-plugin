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
                new ColumnDefinition("id", "INT", false, true, true, null),
                new ColumnDefinition("name", "VARCHAR(80)", true, false, false, "'unknown'")
        ), "dev2");

        assertEquals("""
                CREATE TABLE "DEV2"."T_WRITE_TEST" (
                  "ID" INT IDENTITY(1,1) NOT NULL,
                  "NAME" VARCHAR(80) DEFAULT 'unknown',
                  PRIMARY KEY ("ID")
                )""", sql.get(0));
    }

    @Test
    void altersColumnWithRenameAndModifyStatements() {
        var statements = DamengSql.alterColumnSql(
                "orders",
                new ColumnDefinition("name", "VARCHAR(40)", true, false, false, null),
                new ColumnDefinition("display_name", "VARCHAR(80)", false, false, false, "'n/a'"),
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
                new ColumnDefinition("id", "INT", false, false, false, null),
                new ColumnDefinition("id", "INT", false, true, false, null),
                null
        ));
    }
}
