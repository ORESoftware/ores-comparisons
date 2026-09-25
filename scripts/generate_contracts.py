#!/usr/bin/env python3
from __future__ import annotations
import json
import re
import sys
from pathlib import Path

SAFE = re.compile(r"^[a-z][a-z0-9_]*$")
PROJECT = Path(sys.argv[1]).resolve()
CHECK = "--check" in sys.argv[2:]
schema = json.loads((PROJECT / "contracts/json-schema/domain.schema.json").read_text())
projection = json.loads((PROJECT / "contracts/projection.json").read_text())
defs = schema.get("$defs", {})

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

sql = ["-- GENERATED. DO NOT EDIT.", "BEGIN;"]
for table in projection["tables"]:
    name = safe(table["name"])
    model = table["model"]
    if model not in defs:
        raise SystemExit(f"missing schema model {model}")
    columns = []
    for col in table["columns"]:
        cname = safe(col["name"])
        require_field(model, col["source"])
        suffix = "" if col.get("nullable", False) else " NOT NULL"
        columns.append(f"  {cname} {col['sql_type']}{suffix}")
    pk = safe(table["primary_key"])
    columns.append(f"  PRIMARY KEY ({pk})")
    sql += [f"CREATE TABLE IF NOT EXISTS {name} (", ",\n".join(columns), ");"]
sql += ["COMMIT;", ""]

seed = ["-- GENERATED. DO NOT EDIT.", "BEGIN;"]
for item in projection.get("seed", []):
    table = safe(item["table"])
    for row in item["rows"]:
        names = [safe(name) for name in row]
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
for message in proto_cfg["messages"]:
    proto.append(f"message {message['name']} {{")
    for field in message["fields"]:
        proto.append(f"  {field['type']} {field['name']} = {field['number']};")
    proto += ["}", ""]
proto.append(f"service {proto_cfg['service']} {{")
for rpc in proto_cfg["rpcs"]:
    proto.append(f"  rpc {rpc['name']} ({rpc['request']}) returns ({rpc['response']});")
proto += ["}", ""]

typescript = ["// GENERATED. DO NOT EDIT."]
rust = ["// GENERATED. DO NOT EDIT.", "#![allow(dead_code)]"]
gleam = ["// GENERATED. DO NOT EDIT."]

for iface in projection["interfaces"]:
    model = iface["model"]
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
