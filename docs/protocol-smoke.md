# DM Plugin Protocol Smoke Test

This repository includes a direct JSON-RPC smoke test for the DM plugin. It runs the plugin jar as a local process, sends one JSON-RPC request per line, and validates the main metadata, query, write, DDL preview, BLOB/CLOB, batch, view, trigger, and explain paths.

The smoke test does not require Tabularis to be running.

## Requirements

- Java 17+
- A built plugin jar, for example after `mvn clean package`
- A running DM instance
- A local Dameng JDBC jar
- A user/schema with permission to create/drop smoke objects

## Usage

```bash
export DM_JDBC_DRIVER_PATH="/path/to/DmJdbcDriver8.jar"
export DM_HOST="127.0.0.1"
export DM_PORT="5236"
export DM_USER="DEV2"
export DM_PASSWORD="Dev2_123456@"
export DM_SCHEMA="DEV2"

bash scripts/protocol-smoke.sh
```

Optional variables:

```bash
export PLUGIN_JAR="target/tabularis-dameng-plugin-1.0.0.jar"
export DM_CONNECT_TIMEOUT_MS="10000"
export DM_QUERY_TIMEOUT_SEC="60"
```

## What It Covers

- `initialize`, `test_connection`, `ping`
- schema/table/column/index/foreign-key metadata
- batch metadata and schema snapshot
- view create/list/definition/columns/drop
- trigger list/create/definition/drop
- query execution, explain, and batch continuation after one bad statement
- row insert/update, empty insert, BLOB/VARBINARY wire format, CLOB text
- DDL preview methods for table, column, index, and foreign key

The script creates temporary objects named `T_SMOKE_DM_*`, `V_SMOKE_DM_LOB`, and `TRG_SMOKE_DM`, then drops them before exiting.

## Notes

- The smoke test intentionally does not package or reference any JDBC driver from the repository.
- If a local DEV2 user does not exist, run the same test with any schema-owning user by changing `DM_USER`, `DM_PASSWORD`, and `DM_SCHEMA`.
- Trigger creation depends on DM accepting the single JDBC statement form used by the plugin. If this fails while trigger metadata browsing works, record the error for the upstream SQL splitter/trigger workflow discussion.
