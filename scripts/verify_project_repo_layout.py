#!/usr/bin/env python3
from __future__ import annotations

import subprocess
from pathlib import Path

from project_matrix import ROOT, load_project_specs

errors: list[str] = []

try:
    specs = load_project_specs()
except Exception as exc:
    raise SystemExit(f"invalid project matrix: {exc}") from exc

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
actual = {
    p
    for p in ROOT.glob("stacks/*/projects/*")
    if p.is_dir() and (p / "repos/readme.md").is_file()
}

for project in sorted(expected - actual):
    errors.append(f"matrix project is missing: {project.relative_to(ROOT)}")
for project in sorted(actual - expected):
    errors.append(f"project exists outside shared/project-matrix.json: {project.relative_to(ROOT)}")

for spec in specs:
    project = spec.path
    repos = spec.repos_path
    shared = spec.shared_repo_path
    app = spec.app_repo_path
    rel = project.relative_to(ROOT)

    if not project.is_dir():
        continue

    project_entries = sorted(child.name for child in project.iterdir())
    if project_entries != ["repos"]:
        errors.append(
            f"{rel} project envelope must contain only repos/, found {project_entries}"
        )

    if not repos.is_dir():
        errors.append(f"{rel} missing project-owned repos/ organization mirror")
        continue

    for child in repos.iterdir():
        if child.is_file() and child.name != "readme.md":
            errors.append(
                f"{rel}/repos contains top-level file {child.name}; only readme.md is allowed"
            )

    if not (repos / "readme.md").is_file():
        errors.append(f"{rel} missing repos/readme.md")

    shared_is_gitlink = shared in gitlinks
    app_is_gitlink = app in gitlinks

    if not shared.is_dir() and not shared_is_gitlink:
        errors.append(f"{rel} missing repos/.github organization authority repository")
    if not app.is_dir() and not app_is_gitlink:
        errors.append(f"{rel} missing repos/app application repository")

    # Content checks run whenever the repository is materialized or the submodule
    # has been initialized. An uninitialized gitlink is validated structurally by
    # verify_dummy_org_map.py and is intentionally not dereferenced here.
    if shared.is_dir():
        for required in (
            "README.md",
            "profile/README.md",
            "comparison.toml",
            ".ores-compose.yaml",
            ".zpkg.toml",
            ".sops.yaml",
            ".env.example",
            "contracts",
            "conformance",
            "governance",
            "env",
            "scripts",
        ):
            if not (shared / required).exists():
                errors.append(f"{rel} repos/.github missing {required}")

    if app.is_dir():
        if spec.stack == "beamscale":
            for required in (".ores-lambda.toml", "bmscl-policy.toml"):
                if not (app / required).is_file():
                    errors.append(f"{rel} repos/app missing {required}")
            if not list((app / "lambdas").glob("**/gleam.toml")):
                errors.append(f"{rel} repos/app has no BeamScale Gleam lambda")
        elif spec.stack == "scintilla-run":
            if not list((app / "endpoints").glob("**/.scintilla-endpoint.toml")):
                errors.append(f"{rel} repos/app has no Scintilla endpoint")
        elif spec.stack == "ores-stack":
            for required in (
                ".ores-stack.toml",
                "Cargo.toml",
                "contracts/service.route-map.json",
            ):
                if not (app / required).is_file():
                    errors.append(f"{rel} repos/app missing {required}")
            if not (app / "src").is_dir():
                errors.append(f"{rel} repos/app missing src/")

for stack_root in sorted(ROOT.glob("stacks/*")):
    if (stack_root / "repos").exists():
        errors.append(
            f"{stack_root.relative_to(ROOT)}/repos is forbidden; repos/ belongs under each project"
        )

for submodule in sorted(gitlinks):
    path = submodule.relative_to(ROOT)
    owner = next(
        (spec for spec in specs if submodule.is_relative_to(spec.repos_path)),
        None,
    )
    if owner is None:
        errors.append(
            f"git submodule {path} is outside a matrix-governed project repos/ org mirror"
        )
    elif submodule.parent != owner.repos_path:
        errors.append(
            f"git submodule {path} must be a direct repository child of "
            f"{owner.repos_path.relative_to(ROOT)}"
        )

if errors:
    print("project repo-layout verification FAILED")
    for error in errors:
        print(f" - {error}")
    raise SystemExit(1)

print(
    f"project repo-layout verification OK: {len(expected)} projects expose only "
    "repos/{readme.md,.github/,repo...}; repository children may be materialized "
    "or governed gitlinks"
)
