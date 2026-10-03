#!/usr/bin/env python3
from __future__ import annotations

import json
import shutil
import subprocess
import tomllib
from pathlib import Path

from project_matrix import ROOT, load_project_specs

SCHEMA_PATH = ROOT / "shared/github-org-contract/json-schema/domain.schema.json"
schema = json.loads(SCHEMA_PATH.read_text())
defs = schema["$defs"]
repo_schema = defs["RepositoryContract"]
errors: list[str] = []

EXPECTED = {
    "big-org-example-commerce": {
        "catalog-service": ("service", "catalog"),
        "orders-worker": ("worker", "orders"),
        "storefront-web": ("frontend", "storefront"),
    },
    "big-org-example-collaboration": {
        "presence-service": ("service", "presence"),
        "message-worker": ("worker", "messages"),
        "workspace-web": ("frontend", "workspace"),
    },
    "big-org-example-operations": {
        "ingest-service": ("service", "ingest"),
        "automation-worker": ("worker", "automation"),
        "ops-console": ("frontend", "ops"),
    },
}

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
                out.append(f"{path}: missing {name!r}")
            if spec.get("unevaluatedProperties") is False:
                for name in sorted(set(value) - set(props)):
                    out.append(f"{path}: unexpected {name!r}")
            for name, item in value.items():
                if name in props:
                    out += validate(item, props[name], f"{path}.{name}")
    return out

for spec in load_project_specs():
    if spec.scenario not in EXPECTED:
        continue
    org = spec.repos_path
    manifest = json.loads((spec.shared_repo_path / "org.manifest.json").read_text())
    by_name = {entry["name"]: entry for entry in manifest["repositories"]}
    expected = EXPECTED[spec.scenario]

    missing = set(expected) - set(by_name)
    if missing:
        errors.append(f"{org.relative_to(ROOT)} missing domain repos in manifest: {sorted(missing)}")

    for name, (kind, route) in expected.items():
        repo = org / name
        rel = repo.relative_to(ROOT)
        if not repo.is_dir():
            errors.append(f"{rel}: repository directory missing")
            continue

        contract_path = repo / "repo.contract.json"
        try:
            contract = json.loads(contract_path.read_text())
        except Exception as exc:
            errors.append(f"{rel}: invalid repo.contract.json: {exc}")
            continue

        for error in validate(contract, repo_schema):
            errors.append(f"{rel}: {error}")

        expected_values = {
            "schema": "ores.comparisons.repo-contract/v1",
            "name": name,
            "stack": spec.stack,
            "scenario": spec.scenario,
            "kind": kind,
            "contractAuthority": "../.github/contracts/json-schema/domain.schema.json",
            "generatedSdk": "../sdk-typescript",
            "governanceRepo": "../.github",
            "healthPath": "/healthz",
            "logicalRoute": f"/v1/{route}",
        }
        for key, expected_value in expected_values.items():
            if contract.get(key) != expected_value:
                errors.append(
                    f"{rel}: repo contract {key}={contract.get(key)!r}, expected {expected_value!r}"
                )

        manifest_entry = by_name.get(name, {})
        if manifest_entry.get("kind") != kind:
            errors.append(f"{rel}: manifest kind {manifest_entry.get('kind')!r}, expected {kind!r}")

        authority = (repo / contract["contractAuthority"]).resolve()
        generated_sdk = (repo / contract["generatedSdk"]).resolve()
        governance = (repo / contract["governanceRepo"]).resolve()
        if authority != (spec.shared_repo_path / "contracts/json-schema/domain.schema.json").resolve():
            errors.append(f"{rel}: contractAuthority escapes sibling .github authority")
        if generated_sdk != (org / "sdk-typescript").resolve():
            errors.append(f"{rel}: generatedSdk must resolve to sibling sdk-typescript")
        if governance != spec.shared_repo_path.resolve():
            errors.append(f"{rel}: governanceRepo must resolve to sibling .github")

        if spec.stack == "beamscale":
            for required in (".ores-lambda.toml", "bmscl-policy.toml"):
                if not (repo / required).is_file():
                    errors.append(f"{rel}: missing {required}")
            gleam_tomls = list((repo / "lambdas").glob("*/gleam.toml"))
            gleam_sources = list((repo / "lambdas").glob("*/src/main.gleam"))
            if len(gleam_tomls) != 1 or len(gleam_sources) != 1:
                errors.append(f"{rel}: expected exactly one Gleam lambda entrypoint")
            else:
                try:
                    tomllib.loads(gleam_tomls[0].read_text())
                    tomllib.loads((repo / ".ores-lambda.toml").read_text())
                    tomllib.loads((repo / "bmscl-policy.toml").read_text())
                except Exception as exc:
                    errors.append(f"{rel}: invalid BeamScale TOML: {exc}")
        elif spec.stack == "scintilla-run":
            endpoint = repo / f"endpoints/{route}/.scintilla-endpoint.toml"
            source = repo / f"endpoints/{route}/lambda.mjs"
            try:
                endpoint_cfg = tomllib.loads(endpoint.read_text())
                if endpoint_cfg.get("path") != f"/v1/{route}":
                    errors.append(f"{rel}: Scintilla endpoint route drift")
            except Exception as exc:
                errors.append(f"{rel}: invalid Scintilla endpoint config: {exc}")
            if not source.is_file():
                errors.append(f"{rel}: missing lambda.mjs")
            elif shutil.which("node"):
                result = subprocess.run(
                    ["node", "--check", str(source)],
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                )
                if result.returncode != 0:
                    errors.append(f"{rel}: Node syntax check failed: {result.stdout.strip()}")
        elif spec.stack == "ores-stack":
            for required in ("Cargo.toml", ".ores-stack.toml", "contracts/service.route-map.json", "src/main.rs"):
                if not (repo / required).is_file():
                    errors.append(f"{rel}: missing {required}")
            try:
                cargo = tomllib.loads((repo / "Cargo.toml").read_text())
                if cargo.get("package", {}).get("edition") != "2021":
                    errors.append(f"{rel}: unexpected Rust edition")
                tomllib.loads((repo / ".ores-stack.toml").read_text())
                routes = json.loads((repo / "contracts/service.route-map.json").read_text())
                route_paths = {entry["path"] for entry in routes.get("routes", [])}
                if "/healthz" not in route_paths or f"/v1/{route}" not in route_paths:
                    errors.append(f"{rel}: ORES Stack route map drift")
            except Exception as exc:
                errors.append(f"{rel}: invalid ORES Stack repo metadata: {exc}")

if errors:
    print("big-org repository verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("big-org repository verification OK: 27 domain-specific sibling repos are governed and stack-native")
