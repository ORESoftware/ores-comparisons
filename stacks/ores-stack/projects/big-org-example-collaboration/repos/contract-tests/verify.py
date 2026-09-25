#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

REPO = Path(__file__).resolve().parent
ORG = REPO.parent
SHARED = ORG / ".github"
SCHEMA = SHARED / "contracts/json-schema/domain.schema.json"
INSTANCES = SHARED / "conformance/instances"


def ref_name(value: str) -> str:
    return value.rsplit("/", 1)[-1]


def validate(value, spec: dict, defs: dict) -> bool:
    if "$ref" in spec:
        target = ref_name(spec["$ref"])
        return target in defs and validate(value, defs[target], defs)
    if "enum" in spec:
        return value in spec["enum"]
    typ = spec.get("type")
    if typ == "string":
        return isinstance(value, str)
    if typ == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if typ == "boolean":
        return isinstance(value, bool)
    if typ == "array":
        return isinstance(value, list) and all(
            validate(item, spec.get("items", {}), defs) for item in value
        )
    if typ == "object":
        if not isinstance(value, dict):
            return False
        props = spec.get("properties", {})
        required = set(spec.get("required", []))
        if not required.issubset(value):
            return False
        if spec.get("unevaluatedProperties") is False and set(value) - set(props):
            return False
        return all(
            name in props and validate(item, props[name], defs)
            for name, item in value.items()
        )
    return True


schema = json.loads(SCHEMA.read_text())
if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
    raise SystemExit("shared authority is not JSON Schema Draft 2020-12")
defs = schema.get("$defs", {})
if not defs:
    raise SystemExit("shared authority has no declarations")

checked = 0
for declaration_dir in sorted(p for p in INSTANCES.iterdir() if p.is_dir()):
    declaration = defs.get(declaration_dir.name)
    if declaration is None:
        raise SystemExit(f"unknown fixture declaration: {declaration_dir.name}")
    for expected, lane_name in ((True, "valid"), (False, "invalid")):
        lane = declaration_dir / lane_name
        if not lane.is_dir():
            continue
        for fixture in sorted(lane.glob("*.json")):
            actual = validate(json.loads(fixture.read_text()), declaration, defs)
            if actual != expected:
                raise SystemExit(
                    f"{fixture.relative_to(ORG)} expected {expected} but got {actual}"
                )
            checked += 1

if checked == 0:
    raise SystemExit("no contract fixtures were checked")

print(f"contract-tests OK: {checked} sibling .github authority fixtures")
