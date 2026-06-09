# Tabularis DM Plugin

[DM / Dameng](https://www.dameng.com/) database driver plugin for [Tabularis](https://github.com/TabularisDB/tabularis).

This plugin runs as a standalone Java process and talks to Tabularis through JSON-RPC 2.0 over stdin/stdout. It loads the official Dameng JDBC driver from a local path configured by the user. Dameng JDBC binaries are not redistributed in this repository or in release artifacts.

[中文文档](./README.zh-CN.md)

## Features

- Connect to Dameng through a user-provided JDBC driver jar
- List schemas, tables, and columns
- List indexes and foreign keys
- List views, inspect view definitions, and load view columns
- List stored functions/procedures and routine parameters
- Return Dameng EXPLAIN plans for Tabularis Visual Explain
- List triggers, inspect trigger definitions, and create/drop triggers through Tabularis trigger UI
- Return batch column and foreign key metadata for faster browsing
- Return schema snapshots for Tabularis ER diagrams
- Execute SQL queries, DML, and DDL from the SQL editor
- Execute multi-statement batches through the plugin-side `execute_query_batch` JSON-RPC
- Insert, update, and delete rows through Tabularis row editing
- Generate table, column, index, and foreign-key management SQL
- Create, alter, and drop views
- Create and drop triggers
- Drop indexes and foreign keys
- Return Tabularis-compatible result sets

Routine execution and routine management are still out of scope. Trigger browse/view/create/drop RPCs are implemented and advertised through `capabilities.triggers`. `execute_query_batch` is also implemented plugin-side; current Tabularis external adapter versions may still send SQL editor batches as repeated `execute_query` calls until an upstream adapter override is added.

## Requirements

- Java 17 or newer
- Maven 3.9+ for building from source
- Tabularis with external plugin support
- A local Dameng JDBC driver jar

Download Dameng database/JDBC packages from the official Dameng download page:

https://www.dameng.com/download/index.html

For modern local development, `DmJdbcDriver8.jar` is the recommended default. If you run into compatibility issues on JDK 21, try `DmJdbcDriver11.jar`.

## Build

```bash
mvn clean package
chmod +x dameng-plugin
```

The executable jar is written to:

```text
target/tabularis-dameng-plugin-0.7.0.jar
```

## Install Locally

Tabularis currently stores user-installed plugins under:

```text
~/Library/Application Support/com.debba.tabularis/plugins/
```

Install this plugin on macOS:

```bash
PLUGIN_DIR="$HOME/Library/Application Support/com.debba.tabularis/plugins/dameng"

mkdir -p "$PLUGIN_DIR/target"
cp manifest.json dameng-plugin dameng-plugin.bat "$PLUGIN_DIR/"
cp target/tabularis-dameng-plugin-0.7.0.jar "$PLUGIN_DIR/target/"
chmod +x "$PLUGIN_DIR/dameng-plugin"
```

Place the JDBC jar in a stable local path, for example:

```bash
mkdir -p "$HOME/Library/Application Support/com.debba.tabularis/jdbc"
cp /path/to/DmJdbcDriver8.jar \
  "$HOME/Library/Application Support/com.debba.tabularis/jdbc/"
```

Restart Tabularis after installing or replacing the plugin.

## Configure in Tabularis

Open `Settings -> Plugins -> DM`, then set:

```text
jdbc_driver_path=/Users/<you>/Library/Application Support/com.debba.tabularis/jdbc/DmJdbcDriver8.jar
```

Create a Dameng connection:

```text
host=127.0.0.1
port=5236
database=<leave empty, or select a loaded schema if Tabularis asks>
username=SYSDBA
password=<your password>
```

Dameng does not require a database name in the JDBC URL. The plugin uses:

```text
jdbc:dm://host:port
```

Schema selection is handled separately through Tabularis.

## Local Demo Schema

For local validation, the `DEV2` schema in the Dameng Docker instance was populated with a small sales dataset:

- Tables: `CUSTOMERS`, `PRODUCTS`, `ORDERS`, `ORDER_ITEMS`, `ORDER_AUDIT`, `T_WRITE_TEST`
- Foreign keys: orders to customers, order items to orders, and order items to products
- Indexes: customer/order/product lookup indexes plus `UX_PRODUCTS_SKU`
- Views: `V_ORDER_SUMMARY`, `V_ORDER_DETAIL`, `V_CUSTOMER_LIFETIME_VALUE`, `V_PRODUCT_SALES`
- Routines: `FN_CUSTOMER_ORDER_COUNT`, `FN_CUSTOMER_TOTAL_AMOUNT`, `P_REFRESH_ORDER_STATS`
- Trigger: `TRG_ORDERS_AUDIT`

Tabularis has been verified locally with this dataset: schemas, tables, columns, indexes, foreign keys, views, view columns, view queries, routines, routine parameters, triggers, trigger definitions, Visual Explain, ER metadata, SQL writes, row editing, and view/index/FK/trigger management paths all work through the `DM` plugin.

The reusable setup script is available at `docs/demo-schema.sql`.

## Development Notes

- stdout is reserved for JSON-RPC responses only.
- logs and diagnostics go to stderr.
- `initialize` loads `dm.jdbc.driver.DmDriver` through `URLClassLoader`.
- `execute_query` accepts SQL editor writes and DDL. Result-set statements return rows; non-result statements return `affected_rows`.
- `execute_query_batch` runs multiple statements in order on one JDBC connection, keeps autocommit enabled, continues after per-statement errors, and returns `{ result, error, execution_time_ms }` per statement. This RPC is ready for direct JSON-RPC tests, but current Tabularis external adapter versions may not forward it yet.
- `get_databases` returns visible schemas so the Tabularis connection dialog has a useful value for "Load Databases".
- `get_schema_snapshot` returns tables, columns, and foreign keys for Tabularis ER diagrams.
- `get_views`, `get_view_definition`, and `get_view_columns` inspect views; `create_view`, `alter_view`, and `drop_view` manage views.
- `get_routines` and `get_routine_parameters` expose read-only stored function/procedure metadata.
- routine definitions use `ALL_SOURCE` when available; otherwise the plugin returns a generated signature.
- `explain_query` uses Dameng `EXPLAIN FOR`, parses JDBC plan rows into Tabularis' `ExplainPlan` tree, and preserves raw plan rows. The older text EXPLAIN parser is kept as a fallback.
- `get_triggers`, `get_trigger_definition`, `create_trigger`, and `drop_trigger` are implemented and advertised with `capabilities.triggers: true`.

## Release Packaging

Release artifacts are named like:

```text
tabularis-dameng-plugin-0.7.0.zip
```

The zip should include:

```text
dameng-plugin
dameng-plugin.bat
manifest.json
target/tabularis-dameng-plugin-0.7.0.jar
```

Do not include:

```text
DmJdbcDriver*.jar
jdbc-*.zip
```

## Troubleshooting

If the driver does not appear in Tabularis, restart Tabularis after copying the plugin directory.

If connection succeeds with `SYSDBA` but fails with another user, confirm the user exists in the current Dameng instance:

```sql
SELECT USERNAME, ACCOUNT_STATUS FROM DBA_USERS ORDER BY USERNAME;
```

If "Load Databases" returns nothing, test the plugin directly:

```bash
"$HOME/Library/Application Support/com.debba.tabularis/plugins/dameng/dameng-plugin"
```

Then send JSON-RPC lines to stdin.

## License

Apache-2.0 for this adapter project. Dameng JDBC driver binaries are owned by Dameng and are not distributed by this repository.
