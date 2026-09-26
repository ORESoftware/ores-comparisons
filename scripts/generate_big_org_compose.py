#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

from project_matrix import ROOT, load_project_specs


def yaml_array(values: list[str]) -> str:
    return "[" + ", ".join(json.dumps(value) for value in values) + "]"


def render(topology: dict) -> str:
    stack = topology["stack"]
    lines = [
        "schema_version: ores.compose.v1",
        f"project: {topology['projectId']}",
        "allow_lazy_start: false",
        "services:",
        "  postgres:",
        "    runtime: host",
        '    command: ["bash", "scripts/postgres-local.sh"]',
        "    healthcheck:",
        '      command: ["bash", "scripts/db-health.sh"]',
        "      interval_ms: 300",
        "      timeout_ms: 250",
        "      retries: 20",
    ]
    if stack == "scintilla-run":
        lines += [
            "  runner:",
            "    runtime: host",
            '    command: ["bash", "scripts/scintilla-runner.sh"]',
            "    build:",
            '      - ["bash", "scripts/scintilla-runtime-build.sh", "runner"]',
            "    healthcheck:",
            '      command: ["curl", "--fail", "--silent", "--show-error", "http://127.0.0.1:8083/readyz"]',
            "      interval_ms: 500",
            "      timeout_ms: 400",
            "      retries: 20",
            "  backend:",
            "    runtime: host",
            '    command: ["bash", "scripts/scintilla-backend.sh"]',
            "    build:",
            '      - ["bash", "scripts/scintilla-runtime-build.sh", "backend"]',
            "    depends_on: [runner]",
            "    healthcheck:",
            '      command: ["curl", "--fail", "--silent", "--show-error", "http://127.0.0.1:8080/readyz"]',
            "      interval_ms: 500",
            "      timeout_ms: 400",
            "      retries: 20",
        ]
    lines += [
        "  db-bootstrap:",
        "    runtime: host",
        '    command: ["bash", "scripts/db-bootstrap.sh"]',
        "    build:",
        '      - ["bash", "scripts/contracts-check.sh"]',
        '      - ["bash", "scripts/db-migrate.sh"]',
        '      - ["bash", "scripts/db-seed.sh"]',
        "    depends_on: [postgres]",
        "    healthcheck:",
        '      command: ["bash", "scripts/db-bootstrap-health.sh"]',
        "      interval_ms: 200",
        "      timeout_ms: 200",
        "      retries: 20",
    ]

    services = topology["services"]
    for service in [item for item in services if item["role"] != "application"]:
        deps = ["db-bootstrap", *service["dependsOn"]]
        if stack == "scintilla-run":
            deps.insert(0, "backend")
        deps = list(dict.fromkeys(deps))
        name = service["name"]
        lines += [
            f"  {name}:",
            "    runtime: host",
            f'    command: ["python3", "scripts/run-sibling.py", "run", "{name}"]',
            "    build:",
            f'      - ["python3", "scripts/run-sibling.py", "build", "{name}"]',
            f"    depends_on: {yaml_array(deps)}",
        ]
        if service.get("healthUrl"):
            lines += [
                "    healthcheck:",
                f'      command: ["curl", "--fail", "--silent", "--show-error", "{service["healthUrl"]}"]',
                "      interval_ms: 400",
                "      timeout_ms: 300",
                "      retries: 20",
            ]

    app = next(item for item in services if item["role"] == "application")
    app_deps = ["db-bootstrap", *app["dependsOn"]]
    if stack == "scintilla-run":
        app_deps.insert(0, "backend")
    app_deps = list(dict.fromkeys(app_deps))
    lines += [
        "  app:",
        "    runtime: host",
        '    command: ["bash", "scripts/dev-server.sh"]',
        f"    depends_on: {yaml_array(app_deps)}",
    ]
    if stack == "ores-stack":
        lines += [
            "    healthcheck:",
            '      command: ["curl", "--fail", "--silent", "--show-error", "http://127.0.0.1:3110/"]',
            "      interval_ms: 500",
            "      timeout_ms: 400",
            "      retries: 20",
        ]
    return "\n".join(lines) + "\n"


check = "--check" in sys.argv[1:]
errors: list[str] = []
count = 0

for spec in load_project_specs():
    if not spec.scenario.startswith("big-org-example-"):
        continue
    topology_path = spec.shared_repo_path / "runtime/topology.json"
    if not topology_path.is_file():
        errors.append(f"missing topology: {topology_path.relative_to(ROOT)}")
        continue
    topology = json.loads(topology_path.read_text())
    content = render(topology)
    output = spec.shared_repo_path / ".ores-compose.yaml"
    count += 1
    if check:
        if not output.is_file() or output.read_text() != content:
            errors.append(f"compose drift: {output.relative_to(ROOT)}")
    else:
        output.write_text(content)

if errors:
    print("big-org compose projection FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print(f"big-org compose projection OK: {count} projects")
