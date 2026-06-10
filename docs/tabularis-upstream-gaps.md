# Tabularis Upstream Gaps for DM Plugin 1.0

This document lists the remaining items that require changes in `TabularisDB/tabularis`. The DM plugin itself implements or documents the plugin-side behavior, but these paths need host support before the full UI can use them.

## External Plugin Batch Execution

The DM plugin implements `execute_query_batch` and verifies it directly through JSON-RPC. Tabularis' driver trait has `execute_batch`, but the external plugin driver should override it and forward to plugin `execute_query_batch` so SQL editor batches share the plugin's single JDBC connection.

## External Plugin BLOB Preview / Export

Tabularis has built-in driver hooks for `save_blob_to_file` and `fetch_blob_as_data_url`. External plugin forwarding should be added so DM can implement these RPCs and support the same BLOB preview/export UI as built-in drivers.

## Oracle / DM SQL Splitter

DM trigger, function, and procedure DDL often uses PL/SQL-style blocks such as:

```sql
CREATE OR REPLACE TRIGGER ...
BEGIN
  ...
END;
/
```

The host SQL splitter should treat those blocks as one executable statement for Oracle-like dialects. The plugin cannot reliably reconstruct statements after the host has split them incorrectly.

## Manifest Schema

The TypeScript runtime model includes `capabilities.triggers`, and the UI consumes it. `plugins/manifest.schema.json` should also include `triggers` so official schema validation matches runtime behavior.

## Plugin Guide Field Shapes

Some examples in `plugins/PLUGIN_GUIDE.md` still show older metadata field names such as `is_primary_key` and `column_default`. The current runtime model consumes `is_pk` and `default_value`. Updating the guide will make external plugin implementations less error-prone.

## Optional Future Object Types

DM can expose sequences, synonyms, and richer constraint metadata. To make those visible in Tabularis, the host would need new capabilities, JSON-RPC methods, sidebar groups, and quick navigator support.
