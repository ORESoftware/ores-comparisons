#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

from project_matrix import ROOT, contract_project_specs

PROJECTS = [spec.shared_repo_path for spec in contract_project_specs()] + [
    ROOT / "benchmarks",
    ROOT / "shared/projection-contract",
]
ID = r'[A-Za-z_][A-Za-z0-9_]*'
errors: list[str] = []

def parse_typespec(text: str) -> dict[str, dict]:
    result: dict[str, dict] = {}

    enum_re = re.compile(
        rf'(?:@id\("([^"]+)"\)\s*)?enum\s+({ID})\s*\{{(.*?)\}}',
        re.DOTALL,
    )
    model_re = re.compile(
        rf'(?:@id\("([^"]+)"\)\s*)?model\s+({ID})\s*\{{(.*?)\}}',
        re.DOTALL,
    )

    for explicit, name, body in enum_re.findall(text):
        key = explicit or name
        values = re.findall(rf'{ID}\s*:\s*"([^"]+)"\s*,?', body)
        if not values:
            raise ValueError(f"enum {key} has no literal values")
        result[key] = {"kind": "enum", "values": values}

    for explicit, name, body in model_re.findall(text):
        key = explicit or name
        fields: dict[str, tuple[str, bool]] = {}
        field_re = re.compile(rf'({ID})(\?)?\s*:\s*({ID})(\[\])?\s*;')
        for field, optional, typ, array_suffix in field_re.findall(body):
            if field in fields:
                raise ValueError(f"duplicate field {key}.{field}")
            full_type = typ + ("[]" if array_suffix else "")
            fields[field] = (full_type, optional != "?")
        if not fields:
            raise ValueError(f"model {key} has no fields")
        result[key] = {"kind": "model", "fields": fields}

    if not result:
        raise ValueError("no TypeSpec declarations discovered")
    return result

def normalize_ref(value: str) -> str:
    if "/" in value:
        return value.rsplit("/", 1)[-1]
    return value

def json_type_matches(value, typ: str) -> bool:
    if typ == "string":
        return isinstance(value, str)
    if typ == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if typ == "boolean":
        return isinstance(value, bool)
    return False

def validate_instance(value, declaration: dict, defs: dict) -> bool:
    if not declaration:
        return True
    if declaration.get("$ref"):
        target = normalize_ref(declaration["$ref"])
        return target in defs and validate_instance(value, defs[target], defs)
    if "enum" in declaration:
        return value in declaration["enum"]
    typ = declaration.get("type")
    if typ in {"string", "integer", "boolean"}:
        return json_type_matches(value, typ)
    if typ == "array":
        if not isinstance(value, list):
            return False
        item_spec = declaration.get("items", {})
        return all(validate_instance(item, item_spec, defs) for item in value)
    if typ != "object" or not isinstance(value, dict):
        return False
    props = declaration.get("properties", {})
    required = set(declaration.get("required", []))
    if not required.issubset(value):
        return False
    if declaration.get("unevaluatedProperties") is False and set(value) - set(props):
        return False
    for name, field_value in value.items():
        spec = props.get(name)
        if spec is None or not validate_instance(field_value, spec, defs):
            return False
    return True

def compare(project: Path) -> None:
    tsp_path = project / "contracts/typespec/main.tsp"
    schema_path = project / "contracts/json-schema/domain.schema.json"
    if not tsp_path.is_file() or not schema_path.is_file():
        return

    parsed = parse_typespec(tsp_path.read_text())
    schema = json.loads(schema_path.read_text())
    defs = schema.get("$defs", {})

    if set(parsed) != set(defs):
        raise ValueError(
            f"declaration set drift: TypeSpec={sorted(parsed)} JSON Schema={sorted(defs)}"
        )

    for name, declaration in parsed.items():
        js = defs[name]
        if declaration["kind"] == "enum":
            if js.get("type") != "string" or js.get("enum") != declaration["values"]:
                raise ValueError(f"enum drift for {name}")
            continue

        if js.get("type") != "object":
            raise ValueError(f"model {name} is not an object schema")
        fields = declaration["fields"]
        props = js.get("properties", {})
        if set(fields) != set(props):
            raise ValueError(f"field set drift for {name}")
        required = set(js.get("required", []))
        expected_required = {field for field, (_, required_flag) in fields.items() if required_flag}
        if required != expected_required:
            raise ValueError(f"required-property drift for {name}")

        for field, (typ, _) in fields.items():
            prop = props[field]
            if typ.endswith("[]"):
                base = typ[:-2]
                if prop.get("type") != "array":
                    raise ValueError(f"array drift for {name}.{field}: {typ} vs {prop}")
                item = prop.get("items", {})
                if base == "unknown":
                    if item:
                        raise ValueError(f"unknown-array item drift for {name}.{field}: {item}")
                elif base in {"string", "integer", "boolean"}:
                    if item.get("type") != base:
                        raise ValueError(f"array item drift for {name}.{field}: {base} vs {item}")
                elif normalize_ref(item.get("$ref", "")) != base:
                    raise ValueError(f"array reference drift for {name}.{field}: {base} vs {item}")
            elif typ in {"string", "integer", "boolean"}:
                if prop.get("type") != typ:
                    raise ValueError(f"type drift for {name}.{field}: {typ} vs {prop}")
            elif typ == "unknown":
                if prop:
                    raise ValueError(f"unknown field drift for {name}.{field}: {prop}")
            else:
                if normalize_ref(prop.get("$ref", "")) != typ:
                    raise ValueError(f"reference drift for {name}.{field}: {typ} vs {prop}")

    instances = project / "conformance/instances"
    if instances.is_dir():
        for declaration_dir in sorted(p for p in instances.iterdir() if p.is_dir()):
            name = declaration_dir.name
            if name not in defs:
                raise ValueError(f"instance corpus references unknown declaration {name}")
            for expected, dirname in ((True, "valid"), (False, "invalid")):
                lane = declaration_dir / dirname
                if not lane.is_dir():
                    continue
                for fixture in sorted(lane.glob("*.json")):
                    value = json.loads(fixture.read_text())
                    actual = validate_instance(value, defs[name], defs)
                    if actual != expected:
                        raise ValueError(
                            f"{fixture.relative_to(project)} expected {expected} but validated {actual}"
                        )

for project in PROJECTS:
    try:
        compare(project)
    except Exception as exc:
        errors.append(f"{project.relative_to(ROOT)}: {exc}")

if errors:
    print("static authority verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("static authority verification OK: TypeSpec/JSON Schema shapes and corpus agree for all projects")
