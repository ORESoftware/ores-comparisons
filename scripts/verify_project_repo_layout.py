#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
from pathlib import Path

from project_matrix import ROOT, load_project_specs

errors: list[str] = []

try:
    specs = load_project_specs()
except Exception as exc:
    raise SystemExit(f"invalid project matrix: {exc}") from exc

# Runtime language/target fixtures are deliberately outside project-matrix runtime
# certification. They are nevertheless first-class project envelopes once their
# private repositories are pinned as gitlinks.
fleet = json.loads((ROOT / "shared/dummy-org-fleet.json").read_text())
fixture_state = json.loads((ROOT / "shared/runtime-fixture-submodules.json").read_text())
fixture_entries = {item["org"]: item for item in fleet["runtime_fixture_orgs"]}
materialized_fixture_orgs = set(fixture_state["complete_orgs"]) | set(fixture_state["partial_orgs"])
fixture_repos_paths: set[Path] = set()
for org in materialized_fixture_orgs:
    item = fixture_entries.get(org)
    if item is None:
        errors.append(f"runtime fixture submodule state references unknown org {org}")
        continue
    slug = org.removeprefix("ores-dummy-org-")
    fixture_repos_paths.add(ROOT / "stacks" / item["stack"] / "projects" / slug / "repos")

# Oreslang source-portability Gitlinks are a distinct project family. Their
# Gitlinks are source snapshots only, not certified executable runtime fixtures.
portability = json.loads((ROOT / "shared/oreslang-portability.json").read_text())
portability_repos_paths = {
    ROOT / "stacks" / entry["stack"] / "projects" / "oreslang-portability" / "repos"
    for entry in portability["targets"]
    if entry["pins"]
}

graal_ruby_references = json.loads(
    (ROOT / "shared/graal-ruby-references.json").read_text()
)
reference_gitlinks = {
    ROOT / item["path"]
    for item in graal_ruby_references.get("references", [])
}

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

# A directory is still a project even if it omits repos/readme.md. The older
# discovery below intentionally limits scenario content checks to initialized
# mirrors; a rogue project without that file must not evade ownership checks.
assigned_projects = expected | {repos.parent for repos in fixture_repos_paths | portability_repos_paths}
observed_projects = {
    project for project in ROOT.glob("stacks/*/projects/*") if project.is_dir()
}
for project in sorted(observed_projects - assigned_projects):
    errors.append(
        f"unassigned project outside matrix and runtime fixture fleet: {project.relative_to(ROOT)}"
    )

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
    if shared.is_dir() and not shared_is_gitlink:
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

    if app.is_dir() and not app_is_gitlink:
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
    if owner is not None:
        if submodule.parent != owner.repos_path:
            errors.append(
                f"git submodule {path} must be a direct repository child of "
                f"{owner.repos_path.relative_to(ROOT)}"
            )
        continue

    if submodule.parent in fixture_repos_paths:
        continue

    if submodule.parent in portability_repos_paths:
        continue

    if submodule in reference_gitlinks:
        continue

    errors.append(
        f"git submodule {path} is outside a matrix or governed runtime-fixture project repos/ org mirror"
    )

if errors:
    print("project repo-layout verification FAILED")
    for error in errors:
        print(f" - {error}")
    raise SystemExit(1)

print(
    f"project repo-layout verification OK: {len(expected)} runtime projects plus "
    f"{len(fixture_repos_paths)} runtime-fixture and {len(portability_repos_paths)} "
    "Oreslang source-portability project envelopes; repository "
    "children may be materialized or governed gitlinks"
)
