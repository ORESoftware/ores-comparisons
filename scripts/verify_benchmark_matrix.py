#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
benchmark_matrix = json.loads((ROOT / "benchmarks/matrix.json").read_text())
project_matrix = json.loads((ROOT / "shared/project-matrix.json").read_text())
stack_catalog = json.loads((ROOT / "shared/stack-catalog.json").read_text())
errors: list[str] = []

expected = {
    (item.get("stack"), item.get("scenario"))
    for item in project_matrix.get("projects", [])
    if isinstance(item, dict) and item.get("benchmark") is True
}
executables = {
    item.get("id"): item.get("benchmark_executable")
    for item in stack_catalog.get("stacks", [])
    if isinstance(item, dict) and item.get("status") == "verified"
}
seen: set[tuple[str, str]] = set()

for item in benchmark_matrix.get("projects", []):
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

    expected_executable = executables.get(key[0])
    if not isinstance(expected_executable, str) or not expected_executable:
        errors.append(f"{key} stack has no verified benchmark executable in stack catalog")

    commands = list(item.get("build", [])) + list(item.get("deploy", []))
    for argv in commands:
        if not isinstance(argv, list) or not argv or not all(isinstance(value, str) and value for value in argv):
            errors.append(f"{key} contains an invalid argv command")
            continue
        if expected_executable is not None and argv[0] != expected_executable:
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

if benchmark_matrix.get("schema") != "ores.comparisons.smoke-matrix/v1":
    errors.append("benchmark matrix schema drift")

if errors:
    print("benchmark matrix verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

stack_count = len({stack for stack, _scenario in expected})
scenario_count = len({scenario for _stack, scenario in expected})
print(
    f"benchmark matrix verification OK: {len(expected)} projects, "
    f"{stack_count} verified stacks x {scenario_count} benchmark scenarios"
)
