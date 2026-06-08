# tabularis-dameng-plugin

Tabularis plugin for Dameng database.

This project implements a Tabularis external database driver that talks to the host application over JSON-RPC 2.0 via stdin/stdout. The plugin uses the official Dameng JDBC driver at runtime, but does not redistribute Dameng JDBC driver binaries.

## Status

Early project scaffold. The first milestone is a read-only driver:

- connect to Dameng through JDBC
- list schemas, tables, columns, indexes, and views
- execute SQL queries
- return Tabularis-compatible result sets

Write operations and DDL support will be added only after the read-only path is stable.

## JDBC Driver

This repository does not include `DmJdbcDriver*.jar`.

Users must obtain the Dameng JDBC driver from official Dameng channels or Maven Central, then configure the local driver path for the plugin.

Suggested local layout:

```text
lib/
└── DmJdbcDriver8.jar
```

The `lib/*.jar` files are intentionally ignored by Git.

## License

Apache-2.0 for this adapter project. Dameng JDBC driver binaries are owned by Dameng and are not distributed by this repository.
