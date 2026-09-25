#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
matrix = json.loads((ROOT / "benchmarks/matrix.json").read_text())
errors: list[str] = []

expected = {
    (stack, scenario)
    for stack in ("beamscale", "scintilla-run", "ores-stack")
    for scenario in ("http-observability", "forms-chat-workflow", "cached-rpc")
}
seen: set[tuple[str, str]] = set()

for item in matrix.get("projects", []):
    key = (item.get("stack"), item.get("scenario"))
    if key in seen:
        errors.append(f"duplicate benchmark entry {key}")
    seen.add(key)

    project = ROOT / item.get("path", "")
    if not project.is_dir():
        errors.append(f"{key} missing project path {item.get('path')}")

    mode = item.get("deploy_mode")
    if mode not in {"dry-run", "artifact-handoff"}:
        errors.append(f"{key} invalid deploy mode {mode!r}")

    expected_executable = {
        "beamscale": "bmscl",
        "scintilla-run": "scintilla",
        "ores-stack": "ores-stack",
    }.get(key[0])

    commands = list(item.get("build", [])) + list(item.get("deploy", []))
    for argv in commands:
        if not isinstance(argv, list) or not argv or not all(isinstance(value, str) and value for value in argv):
            errors.append(f"{key} contains an invalid argv command")
            continue
        if argv[0] != expected_executable:
            errors.append(f"{key} command must use {expected_executable}, got {argv[0]}")
        if argv[0] in {"sh", "bash", "zsh"} or any(value in {"&&", "||", ";", "|", "-c"} for value in argv):
            errors.append(f"{key} contains shell control syntax")

    if mode == "dry-run":
        deploy = item.get("deploy", [])
        if not deploy:
            errors.append(f"{key} dry-run mode has no deploy command")
        elif not any("--dry-run" in argv for argv in deploy):
            errors.append(f"{key} deploy smoke does not declare --dry-run")
    elif item.get("deploy"):
        errors.append(f"{key} artifact-handoff must not claim a deploy command")

    artifacts = item.get("artifact_candidates", [])
    if not isinstance(artifacts, list) or not artifacts:
        errors.append(f"{key} has no artifact candidates")

if seen != expected:
    errors.append(
        f"benchmark matrix coverage drift: missing={sorted(expected - seen)} extra={sorted(seen - expected)}"
    )

if matrix.get("schema") != "ores.comparisons.smoke-matrix/v1":
    errors.append("benchmark matrix schema drift")

if errors:
    print("benchmark matrix verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("benchmark matrix verification OK: 3 stacks x 3 scenarios")
