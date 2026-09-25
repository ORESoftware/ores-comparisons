#!/usr/bin/env python3
from __future__ import annotations
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "shared/projection-contract/contracts/json-schema/domain.schema.json"

def _normalize_ref(value: str) -> str:
    return value.rsplit("/", 1)[-1]

def _matches_type(value, expected: str) -> bool:
    if expected == "object":
        return isinstance(value, dict)
    if expected == "array":
        return isinstance(value, list)
    if expected == "string":
        return isinstance(value, str)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "boolean":
        return isinstance(value, bool)
    return False

def _validate(value, spec: dict, defs: dict, path: str, errors: list[str]) -> None:
    ref = spec.get("$ref")
    if ref:
        target = _normalize_ref(ref)
        if target not in defs:
            errors.append(f"{path}: unresolved schema ref {ref!r}")
            return
        _validate(value, defs[target], defs, path, errors)
        return
    if not spec:
        return
    enum = spec.get("enum")
    if enum is not None and value not in enum:
        errors.append(f"{path}: value {value!r} is outside enum {enum!r}")
        return
    expected = spec.get("type")
    if expected and not _matches_type(value, expected):
        errors.append(f"{path}: expected {expected}, got {type(value).__name__}")
        return
    if expected == "array":
        item_spec = spec.get("items", {})
        for index, item in enumerate(value):
            _validate(item, item_spec, defs, f"{path}[{index}]", errors)
        return
    if expected == "object":
        props = spec.get("properties", {})
        required = set(spec.get("required", []))
        for name in sorted(required - set(value)):
            errors.append(f"{path}: missing required property {name!r}")
        sealed = spec.get("unevaluatedProperties") is False or spec.get("additionalProperties") is False
        if sealed:
            for name in sorted(set(value) - set(props)):
                errors.append(f"{path}: unexpected property {name!r}")
        for name, item in value.items():
            if name in props:
                _validate(item, props[name], defs, f"{path}.{name}", errors)

def projection_schema() -> dict:
    return json.loads(SCHEMA_PATH.read_text())

def validate_projection(value) -> list[str]:
    schema = projection_schema()
    defs = schema.get("$defs", {})
    root = defs.get("ProjectionContract")
    if root is None:
        return ["projection authority lacks ProjectionContract"]
    errors: list[str] = []
    _validate(value, root, defs, "$", errors)
    return errors

def load_projection(path: Path) -> dict:
    value = json.loads(path.read_text())
    errors = validate_projection(value)
    if errors:
        raise ValueError("; ".join(errors))
    return value
