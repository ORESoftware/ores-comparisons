#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
from pathlib import Path

from project_matrix import ROOT, load_project_specs

SCHEMA_PATH = ROOT / "shared/github-org-contract/json-schema/domain.schema.json"
schema = json.loads(SCHEMA_PATH.read_text())
defs = schema.get("$defs", {})
root_schema = defs.get("OrganizationManifest")
errors: list[str] = []


def ref_name(value: str) -> str:
    return value.rsplit("/", 1)[-1]


def validate(value, spec: dict, path: str = "$") -> list[str]:
    out: list[str] = []
    if "$ref" in spec:
        target = ref_name(spec["$ref"])
        if target not in defs:
            return [f"{path}: unresolved ref {spec['$ref']!r}"]
        return validate(value, defs[target], path)
    if "enum" in spec and value not in spec["enum"]:
        return [f"{path}: {value!r} outside enum {spec['enum']!r}"]
    typ = spec.get("type")
    if typ == "string":
        if not isinstance(value, str):
            out.append(f"{path}: expected string")
    elif typ == "integer":
        if not isinstance(value, int) or isinstance(value, bool):
            out.append(f"{path}: expected integer")
    elif typ == "boolean":
        if not isinstance(value, bool):
            out.append(f"{path}: expected boolean")
    elif typ == "array":
        if not isinstance(value, list):
            out.append(f"{path}: expected array")
        else:
            for index, item in enumerate(value):
                out += validate(item, spec.get("items", {}), f"{path}[{index}]")
    elif typ == "object":
        if not isinstance(value, dict):
            out.append(f"{path}: expected object")
        else:
            props = spec.get("properties", {})
            required = set(spec.get("required", []))
            for name in sorted(required - set(value)):
                out.append(f"{path}: missing required property {name!r}")
            if spec.get("unevaluatedProperties") is False:
                for name in sorted(set(value) - set(props)):
                    out.append(f"{path}: unexpected property {name!r}")
            for name, item in value.items():
                if name in props:
                    out += validate(item, props[name], f"{path}.{name}")
    return out


if root_schema is None:
    raise SystemExit("organization manifest authority lacks OrganizationManifest")

for spec in load_project_specs():
    org = spec.repos_path
    shared = spec.shared_repo_path
    rel = org.relative_to(ROOT)
    manifest_path = shared / "org.manifest.json"

    try:
        manifest = json.loads(manifest_path.read_text())
    except Exception as exc:
        errors.append(f"{rel}: cannot read .github/org.manifest.json: {exc}")
        continue

    for error in validate(manifest, root_schema):
        errors.append(f"{rel}: {error}")

    if manifest.get("schema") != "ores.comparisons.github-org/v1":
        errors.append(f"{rel}: unsupported manifest schema {manifest.get('schema')!r}")
    if manifest.get("stack") != spec.stack or manifest.get("scenario") != spec.scenario:
        errors.append(f"{rel}: manifest identity differs from project matrix")

    repos = manifest.get("repositories", [])
    names = [entry.get("name") for entry in repos if isinstance(entry, dict)]
    if len(names) != len(set(names)):
        errors.append(f"{rel}: duplicate repository names in manifest")
    declared = set(names)

    actual_dirs = {path.name for path in org.iterdir() if path.is_dir()}
    if declared != actual_dirs:
        errors.append(
            f"{rel}: manifest/directory drift declared={sorted(declared)} "
            f"actual={sorted(actual_dirs)}"
        )

    by_name = {entry["name"]: entry for entry in repos if isinstance(entry, dict) and "name" in entry}
    if set(by_name) != declared:
        errors.append(f"{rel}: malformed repository entries")

    expected_kinds = {
        ".github": "governance",
        "app": "application",
        "sdk-typescript": "generated-sdk",
        "contract-tests": "contract-tests",
    }
    for name, kind in expected_kinds.items():
        entry = by_name.get(name)
        if entry is None:
            errors.append(f"{rel}: missing required sibling repo {name}")
            continue
        if entry.get("path") != name:
            errors.append(f"{rel}: repository {name} path must equal its sibling directory name")
        if entry.get("kind") != kind:
            errors.append(f"{rel}: repository {name} kind must be {kind}")

    graph: dict[str, list[str]] = {}
    for name, entry in by_name.items():
        dependencies = entry.get("dependsOn", [])
        graph[name] = dependencies
        for dependency in dependencies:
            if dependency not in declared:
                errors.append(f"{rel}: {name} depends on undeclared repository {dependency}")
            if dependency == name:
                errors.append(f"{rel}: {name} cannot depend on itself")

        repo_path = org / entry.get("path", "")
        if not repo_path.is_dir():
            errors.append(f"{rel}: declared repository directory missing: {repo_path.name}")

        generated_from = entry.get("generatedFrom")
        if entry.get("kind") == "generated-sdk":
            if not generated_from:
                errors.append(f"{rel}: generated repo {name} lacks generatedFrom")
            else:
                source = org / generated_from
                generated = repo_path / "src/generated/domain.ts"
                if not source.is_file():
                    errors.append(f"{rel}: generated source missing: {generated_from}")
                elif not generated.is_file():
                    errors.append(f"{rel}: generated SDK snapshot missing: {generated.relative_to(org)}")
                elif source.read_text() != generated.read_text():
                    errors.append(f"{rel}: generated SDK drift for {name}")

    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str, trail: tuple[str, ...] = ()) -> None:
        if node in visited:
            return
        if node in visiting:
            errors.append(f"{rel}: repository dependency cycle: {' -> '.join((*trail, node))}")
            return
        visiting.add(node)
        for dependency in graph.get(node, []):
            if dependency in graph:
                visit(dependency, (*trail, node))
        visiting.remove(node)
        visited.add(node)

    for name in sorted(graph):
        visit(name)

    contract_tests = org / "contract-tests/verify.py"
    if contract_tests.is_file():
        result = subprocess.run(
            ["python3", str(contract_tests)],
            cwd=org / "contract-tests",
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        if result.returncode != 0:
            errors.append(
                f"{rel}: contract-tests failed:\n{result.stdout.strip()}"
            )
    else:
        errors.append(f"{rel}: contract-tests/verify.py missing")

if errors:
    print("GitHub organization manifest verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print(
    "GitHub organization manifest verification OK: every project has governed "
    ".github/app/sdk-typescript/contract-tests sibling repositories"
)
