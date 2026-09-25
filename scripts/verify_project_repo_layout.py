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

for spec in specs:
    project = spec.path
    rel = project.relative_to(ROOT)
    repos = project / "repos"
    app = repos / "app"
    readme = repos / "readme.md"
    if not repos.is_dir():
        errors.append(f"{rel} missing project-owned repos/ directory")
        continue
    if not readme.is_file():
        errors.append(f"{rel} missing repos/readme.md")
    if not app.is_dir():
        errors.append(f"{rel} missing materialized repos/app repository")

    if spec.stack == "beamscale":
        for forbidden in (".ores-lambda.toml", "bmscl-policy.toml", "lambdas"):
            if (project / forbidden).exists():
                errors.append(f"{rel} flattened BeamScale file escaped repos/app: {forbidden}")
        for required in (".ores-lambda.toml", "bmscl-policy.toml"):
            if not (app / required).is_file():
                errors.append(f"{rel} repos/app missing {required}")
        if not list((app / "lambdas").glob("**/gleam.toml")):
            errors.append(f"{rel} repos/app has no BeamScale Gleam lambda")
    elif spec.stack == "scintilla-run":
        if (project / "endpoints").exists():
            errors.append(f"{rel} flattened Scintilla endpoints/ escaped repos/app")
        if not list((app / "endpoints").glob("**/.scintilla-endpoint.toml")):
            errors.append(f"{rel} repos/app has no Scintilla endpoint")
    elif spec.stack == "ores-stack":
        for forbidden in (".ores-stack.toml", "Cargo.toml", "Cargo.lock", "build.rs", "src"):
            if (project / forbidden).exists():
                errors.append(f"{rel} flattened ORES Stack file escaped repos/app: {forbidden}")
        for required in (".ores-stack.toml", "Cargo.toml", "contracts/service.route-map.json"):
            if not (app / required).is_file():
                errors.append(f"{rel} repos/app missing {required}")
        if not (app / "src").is_dir():
            errors.append(f"{rel} repos/app missing src/")

for stack_root in sorted(ROOT.glob("stacks/*")):
    if (stack_root / "repos").exists():
        errors.append(
            f"{stack_root.relative_to(ROOT)}/repos is forbidden; repos/ belongs under each project"
        )

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

print(f"project repo-layout verification OK: {len(expected)} matrix-governed projects enforce repos/app")
