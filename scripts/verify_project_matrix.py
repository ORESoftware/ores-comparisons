#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import tomllib
from pathlib import Path

from project_matrix import ROOT, load_materialization_specs, load_project_specs

errors: list[str] = []

try:
    specs = load_project_specs()
    materialized_specs = load_materialization_specs()
except Exception as exc:
    raise SystemExit(f"invalid project/materialization matrix: {exc}") from exc

execution_keys = {(spec.stack, spec.scenario) for spec in specs}
materialized_keys = {(spec.stack, spec.scenario) for spec in materialized_specs}
if not execution_keys.issubset(materialized_keys):
    errors.append(
        "runtime project matrix must be a subset of shared/materialization-matrix.json: "
        f"missing={sorted(execution_keys - materialized_keys)}"
    )

index = subprocess.run(
    ["git", "ls-files", "--stage"],
    cwd=ROOT,
    check=True,
    text=True,
    stdout=subprocess.PIPE,
).stdout

gitlinks: set[Path] = set()
for line in index.splitlines():
    metadata, path = line.split("\t", 1)
    mode = metadata.split()[0]
    if mode == "160000":
        gitlinks.add(ROOT / path)

expected = {spec.path for spec in specs}
topology_expected = {spec.path for spec in materialized_specs}
actual = {
    path
    for path in ROOT.glob("stacks/*/projects/*")
    if path.is_dir()
    and (path / "repos/readme.md").is_file()
    and (
        (path / "repos/.github") in gitlinks
        or (path / "repos/.github/comparison.toml").is_file()
    )
}

for path in sorted(expected - actual):
    errors.append(f"runtime matrix project is missing: {path.relative_to(ROOT)}")
for path in sorted(actual - topology_expected):
    errors.append(
        f"project exists outside shared/materialization-matrix.json: {path.relative_to(ROOT)}"
    )

for spec in specs:
    project = spec.path
    shared = spec.shared_repo_path
    rel = project.relative_to(ROOT)

    if not project.is_dir():
        continue
    if not (spec.repos_path / "readme.md").is_file():
        errors.append(f"{rel} missing project-owned repos/readme.md")

    shared_is_gitlink = shared in gitlinks
    if not shared.is_dir() and not shared_is_gitlink:
        errors.append(f"{rel} missing repos/.github repository")
        continue

    # A committed gitlink proves repository identity and immutable checkout
    # provenance. Static CI intentionally does not dereference private dummy
    # repositories. Content-dependent identity and contract checks run when
    # those submodules are initialized in the authorized CI lanes.
    if shared_is_gitlink:
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
        errors.append(
            f"{rel} is contract-enabled but repos/.github lacks contracts/projection.json"
        )
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

print(
    f"project matrix verification OK: {len(specs)} runtime-verified projects are a subset "
    f"of {len(materialized_specs)} materialized GitHub-org-mirrored projects"
)
