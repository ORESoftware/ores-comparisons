#!/usr/bin/env python3
from __future__ import annotations
import json
import re
import sys
from pathlib import Path
from projection_schema import load_projection

SAFE = re.compile(r"^[a-z][a-z0-9_]*$")
SQL_TYPES = {"TEXT", "INTEGER", "TIMESTAMPTZ", "JSONB"}
PROJECT = Path(sys.argv[1]).resolve()
CHECK = "--check" in sys.argv[2:]
schema = json.loads((PROJECT / "contracts/json-schema/domain.schema.json").read_text())
projection = load_projection(PROJECT / "contracts/projection.json")
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

def resolve_ref(spec: dict) -> dict:
    ref = spec.get("$ref")
    if not ref:
        return spec
    name = ref.rsplit("/", 1)[-1]
    if name not in defs:
        raise SystemExit(f"schema reference {ref!r} does not resolve to a local declaration")
    return defs[name]

def enum_domain(spec: dict) -> list | None:
    resolved = resolve_ref(spec)
    values = resolved.get("enum")
    if values is None:
        return None
    if not isinstance(values, list) or not values:
        raise SystemExit("enum domain must be a non-empty array")
    if any(isinstance(value, (dict, list)) for value in values):
        raise SystemExit("SQL enum projection supports scalar enum members only")
    return values

def constraint_name(table: str, column: str) -> str:
    value = f"ck_{table}_{column}_enum"
    if len(value.encode("utf-8")) > 63:
        raise SystemExit(f"generated PostgreSQL constraint name exceeds 63 bytes: {value}")
    return safe(value)

def referenced_name(spec: dict) -> str | None:
    ref = spec.get("$ref")
    if not ref:
        return None
    name = ref.rsplit("/", 1)[-1]
    if name not in defs:
        raise SystemExit(f"schema reference {ref!r} does not resolve to a local declaration")
    return name

def pascal_variant(value) -> str:
    parts = re.findall(r"[A-Za-z0-9]+", str(value))
    if not parts:
        raise SystemExit(f"enum member {value!r} cannot be projected to a language identifier")
    variant = "".join(part[:1].upper() + part[1:] for part in parts)
    if variant[0].isdigit():
        variant = "V" + variant
    return variant

def snake_identifier(value: str) -> str:
    first = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", value)
    second = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", first)
    result = re.sub(r"[^A-Za-z0-9]+", "_", second).strip("_").lower()
    if not result or result[0].isdigit():
        result = "v_" + result
    return result

def proto_enum_token(type_name: str, value) -> str:
    return f"{snake_identifier(type_name)}_{snake_identifier(str(value))}".upper()

def language_types(field: dict) -> tuple[str, str, str]:
    target = referenced_name(field)
    if target is not None:
        return target, target, target
    if field.get("type") == "integer":
        return "number", "i64", "Int"
    if field.get("type") == "boolean":
        return "boolean", "bool", "Bool"
    if field.get("type") == "string":
        return "string", "String", "String"
    raise SystemExit(f"unsupported interface field schema: {field!r}")

def proto_type(field: dict) -> str:
    target = referenced_name(field)
    if target is not None:
        return target
    if field.get("type") == "integer":
        return "int64"
    if field.get("type") == "boolean":
        return "bool"
    if field.get("type") == "string":
        return "string"
    raise SystemExit(f"unsupported protobuf field schema: {field!r}")

def sql_literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"

table_columns: dict[str, set[str]] = {}
domain_constraints: list[tuple[str, str, str, list]] = []
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
        field_spec = defs[model]["properties"][col["source"]]
        enum_values = enum_domain(field_spec)
        if enum_values is not None:
            if sql_type != "TEXT":
                raise SystemExit(
                    f"enum-backed field {model}.{col['source']} must project to TEXT, got {sql_type}"
                )
            domain_constraints.append(
                (name, cname, constraint_name(name, cname), enum_values)
            )
    pk = safe(table["primary_key"])
    if pk not in projected_names:
        raise SystemExit(f"primary key {name}.{pk} is not a projected column")
    columns.append(f"  PRIMARY KEY ({pk})")
    table_columns[name] = projected_names
    sql += [f"CREATE TABLE IF NOT EXISTS {name} (", ",\n".join(columns), ");"]
sql += ["COMMIT;", ""]

domain_sql = [
    "-- GENERATED. DO NOT EDIT.",
    "-- Additive domain constraints projected from JSON Schema enum authorities.",
    "BEGIN;",
]
for table, column, name, values in domain_constraints:
    allowed = ", ".join(sql_literal(value) for value in values)
    domain_sql += [
        "DO $ores$",
        "BEGIN",
        "  IF NOT EXISTS (",
        "    SELECT 1 FROM pg_constraint",
        f"    WHERE conname = {sql_literal(name)}",
        f"      AND conrelid = 'public.{table}'::regclass",
        "  ) THEN",
        f"    ALTER TABLE public.{table}",
        f"      ADD CONSTRAINT {name} CHECK ({column} IN ({allowed}));",
        "  END IF;",
        "END",
        "$ores$;",
    ]
domain_sql += ["COMMIT;", ""]

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
rust = ["// GENERATED. DO NOT EDIT.", "#![allow(dead_code)]"]
gleam = ["// GENERATED. DO NOT EDIT."]

for enum_name, enum_spec in defs.items():
    values = enum_spec.get("enum")
    if values is None:
        continue
    variants = [pascal_variant(value) for value in values]
    if len(set(variants)) != len(variants):
        raise SystemExit(f"enum {enum_name} has colliding generated variants")

    typescript.append(
        "export type "
        + enum_name
        + " = "
        + " | ".join(json.dumps(value) for value in values)
        + ";"
    )
    typescript.append("")

    rust += [
        "#[derive(Clone, Copy, Debug, Eq, PartialEq)]",
        f"pub enum {enum_name} {{",
    ]
    rust += [f"    {variant}," for variant in variants]
    rust += [
        "}",
        "",
        f"impl {enum_name} {{",
        "    pub const fn as_str(self) -> &'static str {",
        "        match self {",
    ]
    rust += [
        f"            Self::{variant} => {json.dumps(str(value))},"
        for variant, value in zip(variants, values)
    ]
    rust += ["        }", "    }", "}", ""]

    gleam += [f"pub type {enum_name} {{"]
    gleam += [f"  {variant}" for variant in variants]
    gleam += ["}", "", f"pub fn {snake_identifier(enum_name)}_to_string(value: {enum_name}) -> String {{", "  case value {"]
    gleam += [
        f"    {variant} -> {json.dumps(str(value))}"
        for variant, value in zip(variants, values)
    ]
    gleam += ["  }", "}", ""]

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
        ts_type, rs_type, gl_type = language_types(field)
        optional = name not in required
        typescript.append(f"  {name}{'?' if optional else ''}: {ts_type};")
        rust_type = f"Option<{rs_type}>" if optional else rs_type
        rust.append(f"    pub {name}: {rust_type},")
        gleam_type = f"Option({gl_type})" if optional else gl_type
        gleam_fields.append(f"    {name}: {gleam_type}")
    typescript += ["}", ""]
    rust += ["}", ""]
    gleam += [",\n".join(gleam_fields), "  )", "}", ""]

domain_proto = [
    "// GENERATED. DO NOT EDIT.",
    'syntax = "proto3";',
    "",
    f"package {proto_cfg['package']}.domain;",
    "",
]
for enum_name, enum_spec in defs.items():
    values = enum_spec.get("enum")
    if values is None:
        continue
    tokens = [proto_enum_token(enum_name, value) for value in values]
    if len(set(tokens)) != len(tokens):
        raise SystemExit(f"enum {enum_name} has colliding protobuf values")
    domain_proto += [
        f"enum {enum_name} {{",
        f"  {snake_identifier(enum_name).upper()}_UNSPECIFIED = 0;",
    ]
    domain_proto += [
        f"  {token} = {index};"
        for index, token in enumerate(tokens, start=1)
    ]
    domain_proto += ["}", ""]

for model_name, model_spec in defs.items():
    if model_spec.get("type") != "object":
        continue
    domain_proto.append(f"message {model_name} {{")
    for index, (name, field) in enumerate(model_spec.get("properties", {}).items(), start=1):
        domain_proto.append(
            f"  {proto_type(field)} {snake_identifier(name)} = {index};"
        )
    domain_proto += ["}", ""]

outputs = {
    "contracts/generated/sql/001_init.sql": "\n".join(sql),
    "contracts/generated/sql/002_seed.sql": "\n".join(seed),
    "contracts/generated/sql/010_domain_constraints.sql": "\n".join(domain_sql),
    "contracts/generated/protobuf/comparison.proto": "\n".join(proto),
    "contracts/generated/protobuf/domain.proto": "\n".join(domain_proto),
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
