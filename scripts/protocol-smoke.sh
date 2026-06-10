#!/usr/bin/env bash
set -euo pipefail

: "${DM_JDBC_DRIVER_PATH:?Set DM_JDBC_DRIVER_PATH to a local DmJdbcDriver jar.}"
: "${DM_USER:?Set DM_USER.}"
: "${DM_PASSWORD:?Set DM_PASSWORD.}"

PLUGIN_JAR="${PLUGIN_JAR:-target/tabularis-dameng-plugin-1.0.0.jar}"
DM_HOST="${DM_HOST:-127.0.0.1}"
DM_PORT="${DM_PORT:-5236}"
DM_SCHEMA="${DM_SCHEMA:-$DM_USER}"
DM_CONNECT_TIMEOUT_MS="${DM_CONNECT_TIMEOUT_MS:-10000}"
DM_QUERY_TIMEOUT_SEC="${DM_QUERY_TIMEOUT_SEC:-60}"

python3 - "$PLUGIN_JAR" <<'PY'
import json
import os
import subprocess
import sys

jar = sys.argv[1]
settings = {
    "jdbc_driver_path": os.environ["DM_JDBC_DRIVER_PATH"],
    "connect_timeout_ms": int(os.environ.get("DM_CONNECT_TIMEOUT_MS", "10000")),
    "query_timeout_sec": int(os.environ.get("DM_QUERY_TIMEOUT_SEC", "60")),
}
params = {
    "host": os.environ.get("DM_HOST", "127.0.0.1"),
    "port": int(os.environ.get("DM_PORT", "5236")),
    "username": os.environ["DM_USER"],
    "password": os.environ["DM_PASSWORD"],
}
schema = os.environ.get("DM_SCHEMA") or os.environ["DM_USER"]

proc = subprocess.Popen(
    ["java", "-jar", jar],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
)
next_id = 1


def call(method, payload=None, allow_error=False):
    global next_id
    req = {"jsonrpc": "2.0", "method": method, "params": payload or {}, "id": next_id}
    next_id += 1
    proc.stdin.write(json.dumps(req, separators=(",", ":")) + "\n")
    proc.stdin.flush()
    line = proc.stdout.readline()
    if not line:
        raise RuntimeError(f"plugin exited before responding to {method}")
    res = json.loads(line)
    if "error" in res and not allow_error:
        raise RuntimeError(f"{method} failed: {res['error']['message']}")
    return res


def result(method, payload=None, allow_error=False):
    res = call(method, payload, allow_error=allow_error)
    return res.get("result")


def assert_true(condition, message):
    if not condition:
        raise AssertionError(message)


try:
    result("initialize", {"settings": settings})
    result("test_connection", {"params": params})
    result("ping", {"params": params})

    setup = [
        "DROP TRIGGER TRG_SMOKE_DM",
        "DROP VIEW V_SMOKE_DM_LOB",
        "DROP TABLE T_SMOKE_DM_AUDIT",
        "DROP TABLE T_SMOKE_DM_LOB",
        "DROP TABLE T_SMOKE_DM_DEFAULT",
        "CREATE TABLE T_SMOKE_DM_DEFAULT (ID INT IDENTITY(1,1) NOT NULL, STATUS VARCHAR(20) DEFAULT 'NEW' NOT NULL, SCORE INT DEFAULT 0, CONSTRAINT PK_T_SMOKE_DM_DEFAULT PRIMARY KEY (ID))",
        "CREATE TABLE T_SMOKE_DM_LOB (ID INT IDENTITY(1,1) NOT NULL, NAME VARCHAR(80), PAYLOAD_BLOB BLOB, PAYLOAD_BIN VARBINARY(200), PAYLOAD_CLOB CLOB, CONSTRAINT PK_T_SMOKE_DM_LOB PRIMARY KEY (ID))",
        "CREATE TABLE T_SMOKE_DM_AUDIT (ID INT IDENTITY(1,1) NOT NULL, ROW_ID INT, CONSTRAINT PK_T_SMOKE_DM_AUDIT PRIMARY KEY (ID))",
    ]
    batch = result("execute_query_batch", {"params": params, "schema": schema, "queries": setup})
    assert_true(batch[-1]["error"] is None, "smoke setup table creation failed")

    result("insert_record", {"params": params, "schema": schema, "table": "T_SMOKE_DM_DEFAULT", "data": {}})
    result("insert_record", {
        "params": params,
        "schema": schema,
        "table": "T_SMOKE_DM_LOB",
        "data": {
            "NAME": "wire",
            "PAYLOAD_BLOB": "BLOB:2:text/plain:aGk=",
            "PAYLOAD_BIN": "data:application/octet-stream;base64,Ynll",
            "PAYLOAD_CLOB": {"hello": "dm"},
        },
        "max_blob_size": 1024,
    })
    result("update_record", {
        "params": params,
        "schema": schema,
        "table": "T_SMOKE_DM_LOB",
        "pk_col": "ID",
        "pk_val": 1,
        "col_name": "PAYLOAD_CLOB",
        "new_val": "updated clob",
        "max_blob_size": 1024,
    })
    rows = result("execute_query", {"params": params, "schema": schema, "query": "SELECT ID, NAME, PAYLOAD_BLOB, PAYLOAD_BIN, PAYLOAD_CLOB FROM T_SMOKE_DM_LOB ORDER BY ID"})
    assert_true(rows["rows"][0][2].startswith("BLOB:2:application/octet-stream:"), "BLOB query did not return Tabularis wire format")
    assert_true(rows["rows"][0][3].startswith("BLOB:3:application/octet-stream:"), "VARBINARY query did not return Tabularis wire format")
    assert_true(rows["rows"][0][4] == "updated clob", "CLOB update did not round-trip")

    result("get_schemas", {"params": params})
    result("get_tables", {"params": params, "schema": schema})
    result("get_columns", {"params": params, "schema": schema, "table": "T_SMOKE_DM_LOB"})
    result("get_indexes", {"params": params, "schema": schema, "table": "T_SMOKE_DM_LOB"})
    result("get_foreign_keys", {"params": params, "schema": schema, "table": "T_SMOKE_DM_LOB"})
    result("get_all_columns_batch", {"params": params, "schema": schema, "tables": ["T_SMOKE_DM_LOB"]})
    result("get_all_foreign_keys_batch", {"params": params, "schema": schema, "tables": ["T_SMOKE_DM_LOB"]})
    result("get_schema_snapshot", {"params": params, "schema": schema})

    result("create_view", {"params": params, "schema": schema, "view_name": "V_SMOKE_DM_LOB", "definition": "SELECT ID, NAME FROM T_SMOKE_DM_LOB"})
    result("get_views", {"params": params, "schema": schema})
    view_def = result("get_view_definition", {"params": params, "schema": schema, "view_name": "V_SMOKE_DM_LOB"})
    assert_true("T_SMOKE_DM_LOB" in view_def.upper(), "view definition did not contain smoke table")
    result("get_view_columns", {"params": params, "schema": schema, "view_name": "V_SMOKE_DM_LOB"})

    result("get_routines", {"params": params, "schema": schema})
    result("get_triggers", {"params": params, "schema": schema})
    result("create_trigger", {
        "params": params,
        "schema": schema,
        "trigger_sql": "CREATE OR REPLACE TRIGGER TRG_SMOKE_DM AFTER UPDATE ON T_SMOKE_DM_LOB FOR EACH ROW BEGIN INSERT INTO T_SMOKE_DM_AUDIT (ROW_ID) VALUES (:NEW.ID); END;",
    })
    result("get_trigger_definition", {"params": params, "schema": schema, "trigger_name": "TRG_SMOKE_DM", "table_name": "T_SMOKE_DM_LOB"})
    result("drop_trigger", {"params": params, "schema": schema, "trigger_name": "TRG_SMOKE_DM", "table_name": "T_SMOKE_DM_LOB"})

    result("explain_query", {"params": params, "schema": schema, "query": "SELECT * FROM T_SMOKE_DM_LOB", "analyze": False})
    bad_batch = result("execute_query_batch", {"params": params, "schema": schema, "queries": ["SELECT 1", "BAD SQL", "SELECT 2"]})
    assert_true(bad_batch[0]["error"] is None and bad_batch[1]["error"] and bad_batch[2]["error"] is None, "batch error continuation failed")

    result("get_create_table_sql", {"schema": schema, "table_name": "T_SMOKE_DDL", "columns": [{"name": "ID", "data_type": "INT", "is_nullable": False, "is_pk": True, "is_auto_increment": True}]})
    result("get_add_column_sql", {"schema": schema, "table": "T_SMOKE_DM_LOB", "column": {"name": "NOTE", "data_type": "VARCHAR(80)", "is_nullable": True}})
    result("get_alter_column_sql", {"schema": schema, "table": "T_SMOKE_DM_LOB", "old_column": {"name": "NAME", "data_type": "VARCHAR(80)", "is_nullable": True}, "new_column": {"name": "DISPLAY_NAME", "data_type": "VARCHAR(120)", "is_nullable": False, "default_value": "'n/a'"}})
    result("get_create_index_sql", {"schema": schema, "table": "T_SMOKE_DM_LOB", "index_name": "IDX_SMOKE_NAME", "columns": ["NAME"], "is_unique": False})
    result("get_create_foreign_key_sql", {"schema": schema, "table": "T_SMOKE_DM_AUDIT", "fk_name": "FK_SMOKE_ROW", "column": "ROW_ID", "ref_table": "T_SMOKE_DM_LOB", "ref_column": "ID"})

    result("drop_view", {"params": params, "schema": schema, "view_name": "V_SMOKE_DM_LOB"})
    result("execute_query_batch", {"params": params, "schema": schema, "queries": ["DROP TABLE T_SMOKE_DM_AUDIT", "DROP TABLE T_SMOKE_DM_LOB", "DROP TABLE T_SMOKE_DM_DEFAULT"]})
    print("DM plugin protocol smoke passed")
finally:
    try:
        proc.stdin.close()
    except Exception:
        pass
    stderr = proc.stderr.read()
    proc.wait(timeout=5)
    if stderr.strip():
        print(stderr, file=sys.stderr, end="" if stderr.endswith("\n") else "\n")
PY
