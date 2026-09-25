#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

projects = sorted(
    p for p in ROOT.glob("stacks/*/projects/*")
    if p.is_dir() and (p / "comparison.toml").is_file()
)
if not projects:
    errors.append("no concrete comparison projects found")

for project in projects:
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

# Any committed git submodule must live below a concrete project's repos/ boundary.
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
    parts = Path(path).parts
    valid = (
        len(parts) >= 6
        and parts[0] == "stacks"
        and parts[2] == "projects"
        and parts[4] == "repos"
    )
    if not valid:
        errors.append(
            f"git submodule {path} is outside stacks/<stack>/projects/<project>/repos/"
        )

if errors:
    print("project repo-layout verification FAILED")
    for error in errors:
        print(f" - {error}")
    raise SystemExit(1)

print(f"project repo-layout verification OK: {len(projects)} projects own repos/ boundaries")
