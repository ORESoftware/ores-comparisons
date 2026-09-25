#!/usr/bin/env python3
from __future__ import annotations
import json
import re
import sys
from pathlib import Path

SAFE = re.compile(r"^[a-z][a-z0-9_]*$")
SQL_TYPES = {"TEXT", "INTEGER", "TIMESTAMPTZ", "JSONB"}
PROJECT = Path(sys.argv[1]).resolve()
CHECK = "--check" in sys.argv[2:]
schema = json.loads((PROJECT / "contracts/json-schema/domain.schema.json").read_text())
projection = json.loads((PROJECT / "contracts/projection.json").read_text())
defs = schema.get("$defs", {})

if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
    raise SystemExit("authored schema must declare JSON Schema Draft 2020-12")
if projection.get("schema") != "ores.comparisons.projection/v1":
    raise SystemExit("unsupported projection schema")

def safe(value: str) -> str:
    if not SAFE.fullmatch(value):
        raise SystemExit(f"unsafe generated identifier: {value!r}")
    return value

def require_field(model: str, field: str) -> None:
    if model not in defs or field not in defs[model].get("properties", {}):
        raise SystemExit(f"projection references missing field {model}.{field}")

def sql_literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"

table_columns: dict[str, set[str]] = {}
sql = ["-- GENERATED. DO NOT EDIT.", "BEGIN;"]
for table in projection["tables"]:
    name = safe(table["name"])
    if name in table_columns:
        raise SystemExit(f"duplicate table projection {name}")
    model = table["model"]
    if model not in defs or defs[model].get("type") != "object":
        raise SystemExit(f"table {name} references missing/non-model schema {model}")
    columns = []
    projected_names: set[str] = set()
    for col in table["columns"]:
        cname = safe(col["name"])
        if cname in projected_names:
            raise SystemExit(f"duplicate projected column {name}.{cname}")
        projected_names.add(cname)
        require_field(model, col["source"])
        sql_type = col["sql_type"]
        if sql_type not in SQL_TYPES:
            raise SystemExit(f"unapproved SQL type {sql_type!r} for {name}.{cname}")
        suffix = "" if col.get("nullable", False) else " NOT NULL"
        columns.append(f"  {cname} {sql_type}{suffix}")
    pk = safe(table["primary_key"])
    if pk not in projected_names:
        raise SystemExit(f"primary key {name}.{pk} is not a projected column")
    columns.append(f"  PRIMARY KEY ({pk})")
    table_columns[name] = projected_names
    sql += [f"CREATE TABLE IF NOT EXISTS {name} (", ",\n".join(columns), ");"]
sql += ["COMMIT;", ""]

seed = ["-- GENERATED. DO NOT EDIT.", "BEGIN;"]
for item in projection.get("seed", []):
    table = safe(item["table"])
    if table not in table_columns:
        raise SystemExit(f"seed references unknown projected table {table}")
    for row in item["rows"]:
        names = [safe(name) for name in row]
        unknown = set(names) - table_columns[table]
        if unknown:
            raise SystemExit(f"seed for {table} contains unknown columns {sorted(unknown)}")
        values = [sql_literal(row[name]) for name in row]
        seed.append(
            f"INSERT INTO {table} ({', '.join(names)}) VALUES ({', '.join(values)}) "
            "ON CONFLICT DO NOTHING;"
        )
seed += ["COMMIT;", ""]

proto_cfg = projection["proto"]
proto = [
    "// GENERATED. DO NOT EDIT.",
    'syntax = "proto3";',
    "",
    f"package {proto_cfg['package']};",
    "",
]
message_names = set()
for message in proto_cfg["messages"]:
    name = message["name"]
    if name in message_names:
        raise SystemExit(f"duplicate protobuf message {name}")
    message_names.add(name)
    proto.append(f"message {name} {{")
    numbers = set()
    for field in message["fields"]:
        number = field["number"]
        if number <= 0 or number in numbers:
            raise SystemExit(f"invalid/duplicate protobuf field number in {name}: {number}")
        numbers.add(number)
        proto.append(f"  {field['type']} {field['name']} = {number};")
    proto += ["}", ""]
proto.append(f"service {proto_cfg['service']} {{")
for rpc in proto_cfg["rpcs"]:
    if rpc["request"] not in message_names or rpc["response"] not in message_names:
        raise SystemExit(f"RPC {rpc['name']} references an unknown message")
    proto.append(f"  rpc {rpc['name']} ({rpc['request']}) returns ({rpc['response']});")
proto += ["}", ""]

typescript = ["// GENERATED. DO NOT EDIT."]
rust = ["// GENERATED. DO NOT EDIT.", "#![allow(dead_code, non_snake_case)]"]
gleam = ["// GENERATED. DO NOT EDIT."]

for iface in projection["interfaces"]:
    model = iface["model"]
    if model not in defs or defs[model].get("type") != "object":
        raise SystemExit(f"interface projection references missing model {model}")
    spec = defs[model]
    required = set(spec.get("required", []))
    typescript.append(f"export interface {model} {{")
    rust += ["#[derive(Clone, Debug, PartialEq)]", f"pub struct {model} {{"]
    gleam += [f"pub type {model} {{", f"  {model}("]
    gleam_fields = []
    for name, field in spec["properties"].items():
        if field.get("type") == "integer":
            ts_type, rs_type, gl_type = "number", "i64", "Int"
        elif field.get("type") == "boolean":
            ts_type, rs_type, gl_type = "boolean", "bool", "Bool"
        else:
            ts_type, rs_type, gl_type = "string", "String", "String"
        optional = name not in required
        typescript.append(f"  {name}{'?' if optional else ''}: {ts_type};")
        rust_type = f"Option<{rs_type}>" if optional else rs_type
        rust.append(f"    pub {name}: {rust_type},")
        gleam_type = f"Option({gl_type})" if optional else gl_type
        gleam_fields.append(f"    {name}: {gleam_type}")
    typescript += ["}", ""]
    rust += ["}", ""]
    gleam += [",\n".join(gleam_fields), "  )", "}", ""]

outputs = {
    "contracts/generated/sql/001_init.sql": "\n".join(sql),
    "contracts/generated/sql/002_seed.sql": "\n".join(seed),
    "contracts/generated/protobuf/comparison.proto": "\n".join(proto),
    "contracts/generated/interfaces/typescript.ts": "\n".join(typescript),
    "contracts/generated/interfaces/rust.rs": "\n".join(rust),
    "contracts/generated/interfaces/gleam.gleam": "\n".join(gleam),
    "contracts/generated/validation/domain.schema.json": json.dumps(schema, indent=2) + "\n",
}

drift = []
for relative, content in outputs.items():
    path = PROJECT / relative
    if CHECK:
        if not path.is_file() or path.read_text() != content:
            drift.append(relative)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

if drift:
    print("generated contract drift:")
    for relative in drift:
        print(" -", relative)
    raise SystemExit(1)

print(("checked" if CHECK else "generated"), PROJECT)
