#!/usr/bin/env python3
from __future__ import annotations

import tomllib

from project_matrix import ROOT, load_project_specs

errors: list[str] = []

try:
    specs = load_project_specs()
except Exception as exc:
    raise SystemExit(f"invalid project matrix: {exc}") from exc

expected = {spec.path for spec in specs}
actual = {
    path for path in ROOT.glob("stacks/*/projects/*")
    if path.is_dir() and (path / "repos/.github/comparison.toml").is_file()
}

for path in sorted(expected - actual):
    errors.append(f"matrix project is missing: {path.relative_to(ROOT)}")
for path in sorted(actual - expected):
    errors.append(f"project exists outside shared/project-matrix.json: {path.relative_to(ROOT)}")

for spec in specs:
    project = spec.path
    shared = spec.shared_repo_path
    rel = project.relative_to(ROOT)
    if not project.is_dir():
        continue
    if not (spec.repos_path / "readme.md").is_file():
        errors.append(f"{rel} missing project-owned repos/readme.md")
    if not shared.is_dir():
        errors.append(f"{rel} missing simulated repos/.github repository")
        continue

    try:
        comparison = tomllib.loads((shared / "comparison.toml").read_text())
        if comparison.get("stack") != spec.stack:
            errors.append(f"{rel} stack identity differs from project matrix")
        if comparison.get("scenario") != spec.scenario:
            errors.append(f"{rel} scenario identity differs from project matrix")
    except Exception as exc:
        errors.append(f"{rel} has invalid repos/.github/comparison.toml: {exc}")

    projection = shared / "contracts/projection.json"
    if spec.contracts and not projection.is_file():
        errors.append(f"{rel} is contract-enabled but repos/.github lacks contracts/projection.json")
    if not spec.contracts and projection.exists():
        errors.append(f"{rel} has a projection but matrix contracts=false")

for stack_root in sorted(ROOT.glob("stacks/*")):
    if (stack_root / "repos").exists():
        errors.append(
            f"{stack_root.relative_to(ROOT)}/repos is forbidden; repos/ belongs under each project"
        )

if errors:
    print("project matrix verification FAILED")
    for error in errors:
        print(f" - {error}")
    raise SystemExit(1)

print(f"project matrix verification OK: {len(specs)} GitHub-org-mirrored projects")
