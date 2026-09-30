#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG_PATH = ROOT / "shared/stack-catalog.json"
PROJECT_MATRIX_PATH = ROOT / "shared/project-matrix.json"
DUMMY_ORG_MAP_PATH = ROOT / "shared/dummy-org-map.json"
BENCHMARK_SCHEMA_PATH = ROOT / "benchmarks/contracts/json-schema/benchmark.schema.json"
BENCHMARK_TYPESPEC_PATH = ROOT / "benchmarks/contracts/typespec/main.tsp"
SCHEMA = "ores.comparisons.stack-catalog/v1"

errors: list[str] = []

catalog = json.loads(CATALOG_PATH.read_text())
if catalog.get("schema") != SCHEMA:
    errors.append(f"unexpected stack catalog schema: {catalog.get('schema')!r}")

raw_stacks = catalog.get("stacks")
if not isinstance(raw_stacks, list) or not raw_stacks:
    errors.append("stack catalog must contain a non-empty stacks array")
    raw_stacks = []

stack_ids: list[str] = []
materialized: set[str] = set()
for raw in raw_stacks:
    if not isinstance(raw, dict):
        errors.append(f"stack catalog entry must be an object: {raw!r}")
        continue
    stack_id = raw.get("id")
    if not isinstance(stack_id, str) or re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", stack_id) is None:
        errors.append(f"invalid stack id: {stack_id!r}")
        continue
    stack_ids.append(stack_id)

    for field in ("display_name", "github_owner", "execution_model"):
        value = raw.get(field)
        if not isinstance(value, str) or not value.strip():
            errors.append(f"{stack_id}: {field} must be a non-empty string")

    branch = raw.get("comparison_branch")
    if branch != f"stack/{stack_id}":
        errors.append(f"{stack_id}: comparison_branch must be stack/{stack_id}, found {branch!r}")

    status = raw.get("status")
    if status not in {"registered", "materialized"}:
        errors.append(f"{stack_id}: invalid status {status!r}")
    elif status == "materialized":
        materialized.add(stack_id)

    executable = raw.get("benchmark_executable")
    if status == "materialized":
        if not isinstance(executable, str) or not executable:
            errors.append(f"{stack_id}: materialized stack requires benchmark_executable")
    elif executable is not None:
        errors.append(f"{stack_id}: registered-only stack must not claim a benchmark executable")

    scaffold = ROOT / "stacks" / stack_id / "projects" / "readme.md"
    if not scaffold.is_file():
        errors.append(f"{stack_id}: missing stack scaffold {scaffold.relative_to(ROOT)}")

if len(stack_ids) != len(set(stack_ids)):
    duplicates = sorted({value for value in stack_ids if stack_ids.count(value) > 1})
    errors.append(f"duplicate stack ids: {duplicates}")
registered = set(stack_ids)

project_matrix = json.loads(PROJECT_MATRIX_PATH.read_text())
matrix_stacks = {
    item.get("stack")
    for item in project_matrix.get("projects", [])
    if isinstance(item, dict) and isinstance(item.get("stack"), str)
}
dummy_org_map = json.loads(DUMMY_ORG_MAP_PATH.read_text())
dummy_stacks = set(dummy_org_map.get("stacks", {}))

if materialized != matrix_stacks:
    errors.append(
        f"materialized stack set {sorted(materialized)} != project matrix {sorted(matrix_stacks)}"
    )
if materialized != dummy_stacks:
    errors.append(
        f"materialized stack set {sorted(materialized)} != dummy-org map {sorted(dummy_stacks)}"
    )
if not materialized <= registered:
    errors.append("materialized stacks must be registered")

benchmark_schema = json.loads(BENCHMARK_SCHEMA_PATH.read_text())
schema_stack_values = set(
    benchmark_schema.get("$defs", {}).get("StackKind", {}).get("enum", [])
)
if schema_stack_values != registered:
    errors.append(
        f"benchmark JSON Schema StackKind {sorted(schema_stack_values)} != catalog {sorted(registered)}"
    )

typespec = BENCHMARK_TYPESPEC_PATH.read_text()
match = re.search(r"enum\s+StackKind\s*\{(?P<body>.*?)\}", typespec, flags=re.DOTALL)
if match is None:
    errors.append("benchmark TypeSpec is missing StackKind enum")
else:
    typespec_stack_values = set(re.findall(r':\s*"([a-z0-9-]+)"', match.group("body")))
    if typespec_stack_values != registered:
        errors.append(
            f"benchmark TypeSpec StackKind {sorted(typespec_stack_values)} != catalog {sorted(registered)}"
        )

if errors:
    print("stack catalog verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print(
    f"stack catalog verification OK: registered={len(registered)}, "
    f"materialized={len(materialized)}, pending={len(registered - materialized)}"
)
