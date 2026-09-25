#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import subprocess

from project_matrix import ROOT, load_project_specs

errors: list[str] = []

try:
    specs = load_project_specs()
except Exception as exc:
    raise SystemExit(f"invalid project matrix: {exc}") from exc

expected = {spec.path for spec in specs}
actual = {
    p for p in ROOT.glob("stacks/*/projects/*")
    if p.is_dir() and (p / "comparison.toml").is_file()
}

for project in sorted(expected - actual):
    errors.append(f"matrix project is missing: {project.relative_to(ROOT)}")
for project in sorted(actual - expected):
    errors.append(f"project exists outside shared/project-matrix.json: {project.relative_to(ROOT)}")

for project in sorted(expected):
    rel = project.relative_to(ROOT)
    repos = project / "repos"
    readme = repos / "readme.md"
    if not repos.is_dir():
        errors.append(f"{rel} missing project-owned repos/ directory")
    if not readme.is_file():
        errors.append(f"{rel} missing repos/readme.md")

for stack_root in sorted(ROOT.glob("stacks/*")):
    if (stack_root / "repos").exists():
        errors.append(
            f"{stack_root.relative_to(ROOT)}/repos is forbidden; repos/ belongs under each project"
        )

# Any committed git submodule must live below a matrix-governed project's repos/ boundary.
index = subprocess.run(
    ["git", "ls-files", "--stage"],
    cwd=ROOT,
    check=True,
    text=True,
    stdout=subprocess.PIPE,
).stdout
for line in index.splitlines():
    metadata, path = line.split("\t", 1)
    mode = metadata.split()[0]
    if mode != "160000":
        continue
    submodule = ROOT / path
    owner = next((project for project in expected if submodule.is_relative_to(project / "repos")), None)
    if owner is None:
        errors.append(
            f"git submodule {path} is outside a matrix-governed stacks/<stack>/projects/<project>/repos/"
        )

if errors:
    print("project repo-layout verification FAILED")
    for error in errors:
        print(f" - {error}")
    raise SystemExit(1)

print(f"project repo-layout verification OK: {len(expected)} matrix-governed projects own repos/ boundaries")
