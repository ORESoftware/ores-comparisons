#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path
from urllib.parse import urlparse

from generate_big_org_compose import render
from project_matrix import ROOT, load_project_specs

SCHEMA_PATH = ROOT / "shared/runtime-topology-contract/contracts/json-schema/domain.schema.json"
schema = json.loads(SCHEMA_PATH.read_text())
defs = schema["$defs"]
topology_schema = defs["RuntimeTopology"]
errors: list[str] = []

EXPECTED = {
    "big-org-example-commerce": {
        "catalog-service": ("service", []),
        "orders-worker": ("worker", ["catalog-service"]),
        "storefront-web": ("frontend", ["catalog-service"]),
        "app": ("application", ["catalog-service", "orders-worker", "storefront-web"]),
    },
    "big-org-example-collaboration": {
        "presence-service": ("service", []),
        "message-worker": ("worker", ["presence-service"]),
        "workspace-web": ("frontend", ["presence-service", "message-worker"]),
        "app": ("application", ["presence-service", "message-worker", "workspace-web"]),
    },
    "big-org-example-operations": {
        "ingest-service": ("service", []),
        "automation-worker": ("worker", ["ingest-service"]),
        "ops-console": ("frontend", ["ingest-service", "automation-worker"]),
        "app": ("application", ["ingest-service", "automation-worker", "ops-console"]),
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


count = 0
for spec in load_project_specs():
    if spec.scenario not in EXPECTED:
        continue
    count += 1
    shared = spec.shared_repo_path
    rel = shared.relative_to(ROOT)
    topology_path = shared / "runtime/topology.json"
    manifest_path = shared / "org.manifest.json"
    compose_path = shared / ".ores-compose.yaml"

    try:
        topology = json.loads(topology_path.read_text())
        manifest = json.loads(manifest_path.read_text())
    except Exception as exc:
        errors.append(f"{rel}: cannot load topology/manifest: {exc}")
        continue

    for error in validate(topology, topology_schema):
        errors.append(f"{rel}: {error}")

    if topology.get("schema") != "ores.comparisons.runtime-topology/v1":
        errors.append(f"{rel}: unsupported runtime topology schema")
    if topology.get("stack") != spec.stack or topology.get("scenario") != spec.scenario:
        errors.append(f"{rel}: topology identity differs from project matrix")
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", topology.get("projectId", "")):
        errors.append(f"{rel}: projectId is not a routing-safe label")

    services = topology.get("services", [])
    names = [item.get("name") for item in services if isinstance(item, dict)]
    if len(names) != len(set(names)):
        errors.append(f"{rel}: duplicate runtime service names")
    by_name = {item["name"]: item for item in services if isinstance(item, dict) and "name" in item}

    expected = EXPECTED[spec.scenario]
    if set(by_name) != set(expected):
        errors.append(
            f"{rel}: runtime repo set drift expected={sorted(expected)} actual={sorted(by_name)}"
        )

    manifest_by_name = {item["name"]: item for item in manifest.get("repositories", [])}
    expected_manifest_kind = {
        "service": "service",
        "worker": "worker",
        "frontend": "frontend",
        "application": "application",
    }

    graph: dict[str, list[str]] = {}
    binds: set[str] = set()
    health_urls: set[str] = set()

    for name, (role, deps) in expected.items():
        item = by_name.get(name)
        if item is None:
            continue
        if item.get("repository") != name:
            errors.append(f"{rel}: runtime service {name} must map to same-name sibling repo")
        if item.get("role") != role:
            errors.append(f"{rel}: {name} role drift")
        if item.get("dependsOn") != deps:
            errors.append(f"{rel}: {name} dependency drift: {item.get('dependsOn')!r}")
        graph[name] = list(item.get("dependsOn", []))

        manifest_item = manifest_by_name.get(name)
        if manifest_item is None:
            errors.append(f"{rel}: runtime service {name} is absent from org manifest")
        elif manifest_item.get("kind") != expected_manifest_kind[role]:
            errors.append(f"{rel}: runtime/org role mismatch for {name}")

        repo_dir = spec.repos_path / name
        if not repo_dir.is_dir():
            errors.append(f"{rel}: runtime sibling repo missing: {name}")

        if spec.stack == "ores-stack" and role != "application":
            bind = item.get("localBind")
            health = item.get("healthUrl")
            if not isinstance(bind, str) or not re.fullmatch(r"127\.0\.0\.1:\d{2,5}", bind):
                errors.append(f"{rel}: {name} lacks admitted loopback localBind")
            elif bind in binds:
                errors.append(f"{rel}: duplicate localBind {bind}")
            else:
                binds.add(bind)
            if not isinstance(health, str):
                errors.append(f"{rel}: {name} lacks healthUrl")
            else:
                parsed = urlparse(health)
                if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.path != "/healthz":
                    errors.append(f"{rel}: {name} healthUrl must be loopback /healthz")
                elif f"{parsed.hostname}:{parsed.port}" != bind:
                    errors.append(f"{rel}: {name} healthUrl does not match localBind")
                if health in health_urls:
                    errors.append(f"{rel}: duplicate healthUrl {health}")
                health_urls.add(health)

            main_rs = repo_dir / "src/main.rs"
            stack_toml = repo_dir / ".ores-stack.toml"
            if "BIND_ADDR" not in main_rs.read_text():
                errors.append(f"{rel}: {name} server ignores admitted BIND_ADDR")
            reload_match = re.search(
                r'^reload_bind = "([^"]+)"$',
                stack_toml.read_text(),
                re.MULTILINE,
            )
            if not reload_match or reload_match.group(1) in binds:
                errors.append(f"{rel}: {name} reload sidecar bind is missing or conflicts with service binds")
        elif role != "application":
            if item.get("localBind") is not None or item.get("healthUrl") is not None:
                errors.append(f"{rel}: non-ORES runtime repo {name} must not invent direct HTTP binds")

    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str, trail: tuple[str, ...] = ()) -> None:
        if node in visited:
            return
        if node in visiting:
            errors.append(f"{rel}: runtime dependency cycle: {' -> '.join((*trail, node))}")
            return
        visiting.add(node)
        for dependency in graph.get(node, []):
            if dependency not in graph:
                errors.append(f"{rel}: {node} depends on unknown runtime service {dependency}")
                continue
            visit(dependency, (*trail, node))
        visiting.remove(node)
        visited.add(node)

    for name in sorted(graph):
        visit(name)

    for required in (
        "scripts/run-sibling.py",
        "scripts/db-bootstrap.sh",
        "scripts/db-bootstrap-health.sh",
    ):
        if not (shared / required).is_file():
            errors.append(f"{rel}: missing runtime adapter {required}")

    expected_compose = render(topology)
    if not compose_path.is_file() or compose_path.read_text() != expected_compose:
        errors.append(f"{rel}: .ores-compose.yaml drifted from runtime topology")

    compose = compose_path.read_text()
    for name in expected:
        if f"  {name}:" not in compose:
            errors.append(f"{rel}: compose omits runtime service {name}")
    if "  db-bootstrap:" not in compose:
        errors.append(f"{rel}: compose omits database readiness barrier")
    if spec.stack == "scintilla-run":
        for infra in ("runner", "backend"):
            if f"  {infra}:" not in compose:
                errors.append(f"{rel}: Scintilla compose omits {infra}")

if count != 9:
    errors.append(f"expected 9 big-org runtime topologies, found {count}")

if errors:
    print("big-org runtime verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("big-org runtime verification OK: 9 authored topologies project deterministically into executable ores-compose graphs")
