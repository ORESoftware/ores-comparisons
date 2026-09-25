#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

from project_matrix import ROOT, load_project_specs

MAP_PATH = ROOT / "shared/dummy-org-map.json"
SCHEMA = "ores.comparisons.dummy-org-map/v1"


def git_index() -> dict[str, tuple[str, str]]:
    output = subprocess.run(
        ["git", "ls-files", "--stage"],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    ).stdout
    entries: dict[str, tuple[str, str]] = {}
    for line in output.splitlines():
        metadata, path = line.split("\t", 1)
        mode, sha, _stage = metadata.split()
        entries[path] = (mode, sha)
    return entries


def gitmodules() -> dict[str, dict[str, str]]:
    path = ROOT / ".gitmodules"
    if not path.is_file():
        return {}
    result = subprocess.run(
        [
            "git",
            "config",
            "-f",
            str(path),
            "--get-regexp",
            r"^submodule\..*\.(path|url|branch)$",
        ],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode not in (0, 1):
        raise SystemExit(result.stderr.strip() or "failed to parse .gitmodules")
    modules: dict[str, dict[str, str]] = {}
    pattern = re.compile(r"^submodule\.(.+)\.(path|url|branch)$")
    for line in result.stdout.splitlines():
        key, value = line.split(None, 1)
        match = pattern.fullmatch(key)
        if match is None:
            continue
        name, field = match.groups()
        modules.setdefault(name, {})[field] = value
    return modules


def repo_state(index: dict[str, tuple[str, str]], path: str) -> str | None:
    exact = index.get(path)
    if exact is not None and exact[0] == "160000":
        return "submodule"
    prefix = path + "/"
    if any(item.startswith(prefix) for item in index):
        return "materialized"
    return None


data = json.loads(MAP_PATH.read_text())
errors: list[str] = []
if data.get("schema") != SCHEMA:
    errors.append(f"unexpected dummy-org map schema: {data.get('schema')!r}")

stacks = data.get("stacks")
projects = data.get("projects")
if not isinstance(stacks, dict) or not stacks:
    errors.append("dummy-org map must declare stacks")
    stacks = {}
if not isinstance(projects, dict) or not projects:
    errors.append("dummy-org map must declare projects")
    projects = {}

specs = load_project_specs()
matrix_stacks = {spec.stack for spec in specs}
matrix_projects = {spec.scenario for spec in specs}
if set(stacks) != matrix_stacks:
    errors.append(
        f"dummy-org stack set {sorted(stacks)} != project matrix {sorted(matrix_stacks)}"
    )
if set(projects) != matrix_projects:
    errors.append(
        f"dummy-org project set {sorted(projects)} != project matrix {sorted(matrix_projects)}"
    )

orgs: set[str] = set()
for scenario, project in sorted(projects.items()):
    if not isinstance(project, dict):
        errors.append(f"{scenario}: project mapping must be an object")
        continue
    org = project.get("org")
    repos = project.get("repos")
    if not isinstance(org, str) or not org.startswith("ores-dummy-org-"):
        errors.append(f"{scenario}: invalid dummy org {org!r}")
    elif org in orgs:
        errors.append(f"{scenario}: duplicate dummy org {org}")
    else:
        orgs.add(org)
    if not isinstance(repos, list) or not repos:
        errors.append(f"{scenario}: repos must be a non-empty array")
        continue
    if repos != sorted(set(repos)):
        errors.append(f"{scenario}: repos must be unique and lexically sorted")
    if ".github" not in repos or "app" not in repos:
        errors.append(f"{scenario}: repos must include .github and app")
    if "readme.md" in repos:
        errors.append(f"{scenario}: repos/readme.md is envelope documentation, not a repo")

for stack, config in sorted(stacks.items()):
    branch = config.get("branch") if isinstance(config, dict) else None
    if branch != f"stack/{stack}":
        errors.append(f"{stack}: expected branch stack/{stack}, found {branch!r}")

index = git_index()
modules = gitmodules()
modules_by_path: dict[str, tuple[str, dict[str, str]]] = {}
for name, config in modules.items():
    path = config.get("path")
    if not path:
        errors.append(f"submodule {name!r} has no path")
        continue
    if path in modules_by_path:
        errors.append(f"duplicate .gitmodules path {path}")
    modules_by_path[path] = (name, config)

states: set[str] = set()
expected_submodule_paths: set[str] = set()

for spec in specs:
    project = projects.get(spec.scenario)
    stack = stacks.get(spec.stack)
    if not isinstance(project, dict) or not isinstance(stack, dict):
        continue
    org = project.get("org")
    repos = project.get("repos")
    branch = stack.get("branch")
    if not isinstance(org, str) or not isinstance(repos, list) or not isinstance(branch, str):
        continue

    repos_rel = spec.repos_path.relative_to(ROOT).as_posix()
    readme_path = f"{repos_rel}/readme.md"
    if readme_path not in index:
        errors.append(f"{repos_rel}: missing tracked readme.md envelope documentation")

    expected_names = set(repos)
    observed_names: set[str] = set()
    prefix = repos_rel + "/"
    for tracked in index:
        if not tracked.startswith(prefix):
            continue
        remainder = tracked[len(prefix):]
        top = remainder.split("/", 1)[0]
        if top != "readme.md":
            observed_names.add(top)
    if observed_names != expected_names:
        errors.append(
            f"{repos_rel}: repo set {sorted(observed_names)} != governed set {sorted(expected_names)}"
        )

    for repo in repos:
        path = f"{repos_rel}/{repo}"
        state = repo_state(index, path)
        if state is None:
            errors.append(f"{path}: neither materialized files nor a gitlink are tracked")
            continue
        states.add(state)
        if state == "submodule":
            expected_submodule_paths.add(path)
            module = modules_by_path.get(path)
            if module is None:
                errors.append(f"{path}: gitlink is missing matching .gitmodules metadata")
                continue
            _name, config = module
            expected_url = f"https://github.com/{org}/{repo}.git"
            if config.get("url") != expected_url:
                errors.append(
                    f"{path}: submodule URL {config.get('url')!r} != {expected_url!r}"
                )
            if config.get("branch") != branch:
                errors.append(
                    f"{path}: submodule branch {config.get('branch')!r} != {branch!r}"
                )
        elif path in modules_by_path:
            errors.append(f"{path}: materialized repo unexpectedly has .gitmodules metadata")

for path, (_name, _config) in sorted(modules_by_path.items()):
    if path.startswith("stacks/") and "/projects/" in path and "/repos/" in path:
        if path not in expected_submodule_paths:
            errors.append(f"{path}: unexpected comparison-project submodule")

if len(states) > 1:
    errors.append(
        "comparison repos mix materialized directories and git submodules; migration must be atomic"
    )

mode = next(iter(states), "unknown")
if mode == "submodule" and not modules:
    errors.append("submodule mode requires .gitmodules")
if mode == "materialized" and modules:
    governed = [
        path
        for path in modules_by_path
        if path.startswith("stacks/") and "/projects/" in path and "/repos/" in path
    ]
    if governed:
        errors.append("materialized mode cannot contain governed comparison submodules")

if errors:
    print("dummy-org/submodule verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print(
    f"dummy-org/submodule verification OK: mode={mode}, "
    f"projects={len(projects)}, stacks={len(stacks)}, orgs={len(orgs)}"
)
