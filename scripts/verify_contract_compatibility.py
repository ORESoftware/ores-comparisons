#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_SUFFIX = "contracts/json-schema/domain.schema.json"
PROJECTION_SUFFIX = "contracts/projection.json"


@dataclass(frozen=True)
class Finding:
    code: str
    path: str
    detail: str


def git_text(ref: str, path: str) -> str | None:
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    return result.stdout if result.returncode == 0 else None


def git_paths(ref: str) -> set[str]:
    result = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", ref],
        cwd=ROOT,
        text=True,
        check=True,
        stdout=subprocess.PIPE,
    )
    return {line for line in result.stdout.splitlines() if line}


def load_json_text(text: str, label: str) -> dict[str, Any]:
    try:
        value = json.loads(text)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"invalid JSON in {label}: {exc}") from exc
    if not isinstance(value, dict):
        raise SystemExit(f"expected JSON object in {label}")
    return value


def schema_identity(node: Any) -> tuple[str, Any] | None:
    if not isinstance(node, dict):
        return None
    if "$ref" in node:
        return ("$ref", node["$ref"])
    if "const" in node:
        return ("const", node["const"])
    if "type" in node:
        return ("type", node["type"])
    return None


def compare_schema_node(before: Any, after: Any, path: str, out: list[Finding]) -> None:
    if not isinstance(before, dict) or not isinstance(after, dict):
        if before != after:
            out.append(Finding("schema-shape-changed", path, "schema node changed representation"))
        return

    before_identity = schema_identity(before)
    after_identity = schema_identity(after)
    if before_identity != after_identity:
        out.append(
            Finding(
                "schema-type-changed",
                path,
                f"schema identity changed from {before_identity!r} to {after_identity!r}",
            )
        )
        return

    before_enum = before.get("enum")
    after_enum = after.get("enum")
    if isinstance(before_enum, list):
        if not isinstance(after_enum, list):
            out.append(Finding("schema-enum-removed", path, "enum constraint was removed/replaced"))
        else:
            removed = [value for value in before_enum if value not in after_enum]
            if removed:
                out.append(Finding("schema-enum-value-removed", path, f"removed enum values: {removed!r}"))

    for keyword in ("minimum", "minLength", "minItems", "minProperties"):
        old = before.get(keyword)
        new = after.get(keyword)
        if new is not None and (old is None or new > old):
            out.append(Finding("schema-constraint-tightened", f"{path}/{keyword}", f"{old!r} -> {new!r}"))
    for keyword in ("maximum", "maxLength", "maxItems", "maxProperties"):
        old = before.get(keyword)
        new = after.get(keyword)
        if new is not None and (old is None or new < old):
            out.append(Finding("schema-constraint-tightened", f"{path}/{keyword}", f"{old!r} -> {new!r}"))

    old_pattern = before.get("pattern")
    new_pattern = after.get("pattern")
    if new_pattern is not None and new_pattern != old_pattern:
        out.append(Finding("schema-pattern-tightened", f"{path}/pattern", f"{old_pattern!r} -> {new_pattern!r}"))

    old_format = before.get("format")
    new_format = after.get("format")
    if new_format is not None and new_format != old_format:
        out.append(Finding("schema-format-changed", f"{path}/format", f"{old_format!r} -> {new_format!r}"))

    for closure in ("additionalProperties", "unevaluatedProperties"):
        if before.get(closure) is not False and after.get(closure) is False:
            out.append(Finding("schema-object-closed", f"{path}/{closure}", "object became closed"))

    if before.get("type") == "object":
        old_props = before.get("properties", {}) if isinstance(before.get("properties", {}), dict) else {}
        new_props = after.get("properties", {}) if isinstance(after.get("properties", {}), dict) else {}
        for name, old_child in old_props.items():
            child_path = f"{path}/properties/{name}"
            if name not in new_props:
                out.append(Finding("schema-property-removed", child_path, "property was removed"))
            else:
                compare_schema_node(old_child, new_props[name], child_path, out)
        old_required = set(before.get("required", []))
        new_required = set(after.get("required", []))
        for name in sorted(new_required - old_required):
            out.append(Finding("schema-required-added", f"{path}/required/{name}", "previously optional property became required"))

    if before.get("type") == "array" and "items" in before:
        if "items" not in after:
            out.append(Finding("schema-items-removed", f"{path}/items", "array item schema was removed"))
        else:
            compare_schema_node(before["items"], after["items"], f"{path}/items", out)


def compare_schema(before: dict[str, Any], after: dict[str, Any], project: str) -> list[Finding]:
    findings: list[Finding] = []
    old_defs = before.get("$defs", {}) if isinstance(before.get("$defs", {}), dict) else {}
    new_defs = after.get("$defs", {}) if isinstance(after.get("$defs", {}), dict) else {}
    for name, old_def in old_defs.items():
        path = f"{project}/schema/$defs/{name}"
        if name not in new_defs:
            findings.append(Finding("schema-definition-removed", path, "definition was removed"))
        else:
            compare_schema_node(old_def, new_defs[name], path, findings)
    return findings


def keyed(items: Any, key: str) -> dict[str, dict[str, Any]]:
    if not isinstance(items, list):
        return {}
    return {str(item[key]): item for item in items if isinstance(item, dict) and key in item}


def compare_projection(before: dict[str, Any], after: dict[str, Any], project: str) -> list[Finding]:
    findings: list[Finding] = []
    old_tables = keyed(before.get("tables"), "name")
    new_tables = keyed(after.get("tables"), "name")
    for table_name, old_table in old_tables.items():
        base = f"{project}/projection/tables/{table_name}"
        new_table = new_tables.get(table_name)
        if new_table is None:
            findings.append(Finding("sql-table-removed", base, "projected table was removed"))
            continue
        if old_table.get("primary_key") != new_table.get("primary_key"):
            findings.append(Finding("sql-primary-key-changed", f"{base}/primary_key", f"{old_table.get('primary_key')!r} -> {new_table.get('primary_key')!r}"))
        old_columns = keyed(old_table.get("columns"), "name")
        new_columns = keyed(new_table.get("columns"), "name")
        for column_name, old_col in old_columns.items():
            cpath = f"{base}/columns/{column_name}"
            new_col = new_columns.get(column_name)
            if new_col is None:
                findings.append(Finding("sql-column-removed", cpath, "projected column was removed"))
                continue
            for field in ("sql_type", "source"):
                if old_col.get(field) != new_col.get(field):
                    findings.append(Finding(f"sql-column-{field}-changed", f"{cpath}/{field}", f"{old_col.get(field)!r} -> {new_col.get(field)!r}"))
            if old_col.get("nullable", False) and not new_col.get("nullable", False):
                findings.append(Finding("sql-column-nullability-tightened", f"{cpath}/nullable", "nullable column became NOT NULL"))

    old_proto = before.get("proto", {}) if isinstance(before.get("proto", {}), dict) else {}
    new_proto = after.get("proto", {}) if isinstance(after.get("proto", {}), dict) else {}
    for field in ("package", "service"):
        if old_proto.get(field) != new_proto.get(field):
            findings.append(Finding(f"protobuf-{field}-changed", f"{project}/projection/proto/{field}", f"{old_proto.get(field)!r} -> {new_proto.get(field)!r}"))

    old_messages = keyed(old_proto.get("messages"), "name")
    new_messages = keyed(new_proto.get("messages"), "name")
    for message_name, old_message in old_messages.items():
        mpath = f"{project}/projection/proto/messages/{message_name}"
        new_message = new_messages.get(message_name)
        if new_message is None:
            findings.append(Finding("protobuf-message-removed", mpath, "message was removed"))
            continue
        old_by_number = {int(field["number"]): field for field in old_message.get("fields", [])}
        new_by_number = {int(field["number"]): field for field in new_message.get("fields", [])}
        for number, old_field in old_by_number.items():
            fpath = f"{mpath}/fields/{number}"
            new_field = new_by_number.get(number)
            if new_field is None:
                findings.append(Finding("protobuf-field-removed", fpath, f"field {old_field.get('name')!r} was removed"))
                continue
            if old_field.get("name") != new_field.get("name"):
                findings.append(Finding("protobuf-field-number-reused", fpath, f"field number {number} changed name {old_field.get('name')!r} -> {new_field.get('name')!r}"))
            if old_field.get("type") != new_field.get("type"):
                findings.append(Finding("protobuf-field-type-changed", fpath, f"{old_field.get('type')!r} -> {new_field.get('type')!r}"))

    old_rpcs = keyed(old_proto.get("rpcs"), "name")
    new_rpcs = keyed(new_proto.get("rpcs"), "name")
    for rpc_name, old_rpc in old_rpcs.items():
        rpath = f"{project}/projection/proto/rpcs/{rpc_name}"
        new_rpc = new_rpcs.get(rpc_name)
        if new_rpc is None:
            findings.append(Finding("protobuf-rpc-removed", rpath, "RPC was removed"))
            continue
        for field in ("request", "response"):
            if old_rpc.get(field) != new_rpc.get(field):
                findings.append(Finding("protobuf-rpc-signature-changed", f"{rpath}/{field}", f"{old_rpc.get(field)!r} -> {new_rpc.get(field)!r}"))

    old_interfaces = {item.get("model") for item in before.get("interfaces", []) if isinstance(item, dict)}
    new_interfaces = {item.get("model") for item in after.get("interfaces", []) if isinstance(item, dict)}
    for model in sorted(old_interfaces - new_interfaces):
        findings.append(Finding("generated-interface-removed", f"{project}/projection/interfaces/{model}", "generated interface was removed"))

    return findings


def project_from_contract_path(path: str, suffix: str) -> str:
    return path[: -(len(suffix) + 1)]


def run(base_ref: str) -> list[Finding]:
    base_paths = git_paths(base_ref)
    current_paths = {
        str(path.relative_to(ROOT))
        for path in ROOT.rglob(SCHEMA_SUFFIX)
        if path.is_file() and ".git" not in path.parts
    }
    findings: list[Finding] = []

    base_schemas = {path for path in base_paths if path.endswith(SCHEMA_SUFFIX)}
    current_schemas = {path for path in current_paths if path.endswith(SCHEMA_SUFFIX)}
    for removed in sorted(base_schemas - current_schemas):
        project = project_from_contract_path(removed, SCHEMA_SUFFIX)
        findings.append(Finding("project-contract-removed", project, "project schema contract was removed"))

    for schema_path in sorted(base_schemas & current_schemas):
        project = project_from_contract_path(schema_path, SCHEMA_SUFFIX)
        before_text = git_text(base_ref, schema_path)
        if before_text is None:
            continue
        after_text = (ROOT / schema_path).read_text()
        findings.extend(compare_schema(load_json_text(before_text, f"{base_ref}:{schema_path}"), load_json_text(after_text, schema_path), project))

        projection_path = f"{project}/{PROJECTION_SUFFIX}"
        old_projection = git_text(base_ref, projection_path)
        current_projection_path = ROOT / projection_path
        if old_projection is not None and not current_projection_path.is_file():
            findings.append(Finding("projection-removed", projection_path, "projection contract was removed"))
        elif old_projection is not None and current_projection_path.is_file():
            findings.extend(compare_projection(load_json_text(old_projection, f"{base_ref}:{projection_path}"), load_json_text(current_projection_path.read_text(), projection_path), project))

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="Fail closed on backward-incompatible contract changes")
    parser.add_argument("--base-ref", required=True, help="Git ref to compare against, e.g. origin/main")
    parser.add_argument("--receipt", default="artifacts/contract-compatibility.json")
    args = parser.parse_args()

    findings = run(args.base_ref)
    receipt = {
        "schema": "ores.comparisons.contract-compatibility/v1",
        "base_ref": args.base_ref,
        "status": "passed" if not findings else "failed",
        "breaking_findings": [asdict(finding) for finding in findings],
    }
    receipt_path = ROOT / args.receipt
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")

    if findings:
        print(f"contract compatibility FAILED: {len(findings)} breaking finding(s)")
        for finding in findings:
            print(f" - [{finding.code}] {finding.path}: {finding.detail}")
        return 1
    print("contract compatibility OK: no backward-incompatible schema/projection changes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
