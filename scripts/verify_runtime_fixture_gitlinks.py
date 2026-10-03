#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FLEET = ROOT / "shared/dummy-org-fleet.json"
STATE = ROOT / "shared/runtime-fixture-submodules.json"


def git_index() -> dict[str, tuple[str, str]]:
    result = subprocess.run(
        ["git", "ls-files", "--stage"],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    entries: dict[str, tuple[str, str]] = {}
    for line in result.stdout.splitlines():
        metadata, path = line.split("\t", 1)
        mode, sha, _stage = metadata.split()
        entries[path] = (mode, sha)
    return entries


def gitmodules() -> dict[str, dict[str, str]]:
    result = subprocess.run(
        ["git", "config", "-f", str(ROOT / ".gitmodules"), "--get-regexp", r"^submodule\..*\.(path|url|branch)$"],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode not in (0, 1):
        raise SystemExit(result.stderr.strip() or "failed to parse .gitmodules")
    parsed: dict[str, dict[str, str]] = {}
    pattern = re.compile(r"^submodule\.(.+)\.(path|url|branch)$")
    for line in result.stdout.splitlines():
        key, value = line.split(None, 1)
        match = pattern.fullmatch(key)
        if match:
            name, field = match.groups()
            parsed.setdefault(name, {})[field] = value
    return parsed


def family(org: str, extension: str, roles: list[str]) -> list[str]:
    return [".github"] + [
        f"{org}-" + role.replace("{server_extension}", extension)
        for role in roles
    ]


fleet = json.loads(FLEET.read_text())
state = json.loads(STATE.read_text())
errors: list[str] = []

if state.get("schema") != "ores.comparisons.runtime-fixture-submodules/v1":
    errors.append(f"unexpected runtime fixture submodule schema: {state.get('schema')!r}")

entries = {item["org"]: item for item in fleet["runtime_fixture_orgs"]}
roles = fleet["runtime_fixture_policy"]["repository_family"]["roles"]
all_orgs = set(entries)
complete = set(state.get("complete_orgs", []))
partial = state.get("partial_orgs", {})
pending = set(state.get("pending_orgs", []))
if not isinstance(partial, dict):
    errors.append("partial_orgs must be an object")
    partial = {}
partial_orgs = set(partial)

if complete & partial_orgs or complete & pending or partial_orgs & pending:
    errors.append("complete, partial, and pending org sets must be disjoint")
if complete | partial_orgs | pending != all_orgs:
    errors.append(
        f"submodule state org partition {sorted(complete | partial_orgs | pending)} != fleet {sorted(all_orgs)}"
    )

index = git_index()
modules = gitmodules()
modules_by_path: dict[str, dict[str, str]] = {}
for config in modules.values():
    path = config.get("path")
    if path:
        if path in modules_by_path:
            errors.append(f"duplicate .gitmodules path {path}")
        modules_by_path[path] = config

expected_paths: set[str] = set()
expected_repo_count = 0
for org in sorted(complete | partial_orgs):
    item = entries.get(org)
    if item is None:
        errors.append(f"unknown runtime fixture org {org}")
        continue
    stack = item["stack"]
    slug = org.removeprefix("ores-dummy-org-")
    branch = item.get("default_branch", "main")
    if org in complete:
        repos = family(org, item["server_extension"], roles)
        if len(repos) != 20:
            errors.append(f"{org}: complete repo family must contain 20 repos including .github")
    else:
        repos = partial[org]
        if not isinstance(repos, list) or not repos:
            errors.append(f"{org}: partial repo list must be non-empty")
            continue
        if repos != sorted(set(repos)):
            errors.append(f"{org}: partial repo list must be unique and lexically sorted")
    expected_repo_count += len(repos)

    for repo in repos:
        path = f"stacks/{stack}/projects/{slug}/repos/{repo}"
        expected_paths.add(path)
        indexed = index.get(path)
        if indexed is None or indexed[0] != "160000":
            errors.append(f"{path}: expected pinned gitlink")
            continue
        config = modules_by_path.get(path)
        if config is None:
            errors.append(f"{path}: missing .gitmodules metadata")
            continue
        expected_url = f"https://github.com/{org}/{repo}.git"
        if config.get("url") != expected_url:
            errors.append(f"{path}: url {config.get('url')!r} != {expected_url!r}")
        if config.get("branch") != branch:
            errors.append(f"{path}: branch {config.get('branch')!r} != {branch!r}")

fixture_prefixes = {
    f"stacks/{item['stack']}/projects/{org.removeprefix('ores-dummy-org-')}/repos/"
    for org, item in entries.items()
}
observed_fixture_gitlinks: set[str] = set()
for path, (mode, _sha) in index.items():
    if mode != "160000":
        continue
    if any(path.startswith(prefix) for prefix in fixture_prefixes):
        observed_fixture_gitlinks.add(path)

unexpected = observed_fixture_gitlinks - expected_paths
if unexpected:
    errors.append(f"unexpected runtime fixture gitlinks: {sorted(unexpected)}")

for org in pending:
    item = entries[org]
    prefix = f"stacks/{item['stack']}/projects/{org.removeprefix('ores-dummy-org-')}/repos/"
    leaked = sorted(path for path in observed_fixture_gitlinks if path.startswith(prefix))
    if leaked:
        errors.append(f"{org}: pending org unexpectedly has gitlinks {leaked}")

if errors:
    print("runtime fixture gitlink verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print(
    "runtime fixture gitlink verification OK: "
    f"complete_orgs={len(complete)}, partial_orgs={len(partial_orgs)}, "
    f"pending_orgs={len(pending)}, gitlinks={expected_repo_count}"
)
