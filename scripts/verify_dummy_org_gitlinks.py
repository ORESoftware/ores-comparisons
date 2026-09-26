#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

from project_matrix import ROOT, load_project_specs

MAP_PATH = ROOT / "shared/dummy-org-map.json"
LEDGER_PATH = ROOT / "shared/dummy-org-gitlinks.json"
GITMODULES_PATH = ROOT / ".gitmodules"

MAP_SCHEMA = "ores.comparisons.dummy-org-map/v1"
LEDGER_SCHEMA = "ores.comparisons.dummy-org-gitlinks/v1"
SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def index_entries() -> dict[str, tuple[str, str]]:
    result = subprocess.run(
        ["git", "ls-files", "--stage"],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    out: dict[str, tuple[str, str]] = {}
    for line in result.stdout.splitlines():
        metadata, path = line.split("\t", 1)
        mode, sha, stage = metadata.split()
        if stage != "0":
            raise SystemExit(f"conflicted index entry is not allowed: {path}")
        out[path] = (mode, sha)
    return out


def gitmodules() -> dict[str, dict[str, str]]:
    if not GITMODULES_PATH.is_file():
        return {}
    result = subprocess.run(
        [
            "git",
            "config",
            "-f",
            str(GITMODULES_PATH),
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

    parsed: dict[str, dict[str, str]] = {}
    pattern = re.compile(r"^submodule\.(.+)\.(path|url|branch)$")
    for line in result.stdout.splitlines():
        key, value = line.split(None, 1)
        match = pattern.fullmatch(key)
        if match is None:
            continue
        name, field = match.groups()
        parsed.setdefault(name, {})[field] = value
    return parsed


mapping = json.loads(MAP_PATH.read_text())
ledger = json.loads(LEDGER_PATH.read_text())
errors: list[str] = []

if mapping.get("schema") != MAP_SCHEMA:
    errors.append(f"unexpected map schema: {mapping.get('schema')!r}")
if ledger.get("schema") != LEDGER_SCHEMA:
    errors.append(f"unexpected gitlink ledger schema: {ledger.get('schema')!r}")
if not SHA_RE.fullmatch(str(ledger.get("source_commit", ""))):
    errors.append("gitlink ledger source_commit must be a 40-character lowercase SHA-1")

stacks = mapping.get("stacks", {})
projects = mapping.get("projects", {})
entries = ledger.get("entries", [])
if not isinstance(stacks, dict) or not isinstance(projects, dict):
    errors.append("dummy-org map stacks/projects must be objects")
    stacks = {}
    projects = {}
if not isinstance(entries, list):
    errors.append("gitlink ledger entries must be an array")
    entries = []

matrix = load_project_specs()
matrix_keys = {(spec.stack, spec.scenario) for spec in matrix}
expected: dict[str, dict[str, str]] = {}

for stack, scenario in sorted(matrix_keys):
    stack_cfg = stacks.get(stack)
    project_cfg = projects.get(scenario)
    if not isinstance(stack_cfg, dict) or not isinstance(project_cfg, dict):
        continue
    branch = stack_cfg.get("branch")
    org = project_cfg.get("org")
    repos = project_cfg.get("repos")
    if not isinstance(branch, str) or not isinstance(org, str) or not isinstance(repos, list):
        continue

    for repo in repos:
        path = f"stacks/{stack}/projects/{scenario}/repos/{repo}"
        expected[path] = {
            "org": org,
            "scenario": scenario,
            "repo": repo,
            "stack": stack,
            "path": path,
            "branch": branch,
            "repository": f"{org}/{repo}",
            "url": f"https://github.com/{org}/{repo}.git",
            "module_name": f"{stack}--{scenario}--{repo.replace('.', '_')}",
        }

if len(expected) != 99:
    errors.append(f"governed topology must contain 99 gitlinks, found {len(expected)}")

ledger_by_path: dict[str, dict[str, object]] = {}
for raw in entries:
    if not isinstance(raw, dict):
        errors.append("gitlink ledger contains a non-object entry")
        continue
    path = raw.get("path")
    if not isinstance(path, str):
        errors.append(f"gitlink ledger entry has invalid path: {path!r}")
        continue
    if path in ledger_by_path:
        errors.append(f"duplicate gitlink ledger path: {path}")
    ledger_by_path[path] = raw

if set(ledger_by_path) != set(expected):
    missing = sorted(set(expected) - set(ledger_by_path))
    extra = sorted(set(ledger_by_path) - set(expected))
    if missing:
        errors.append(f"gitlink ledger missing paths: {missing}")
    if extra:
        errors.append(f"gitlink ledger has unexpected paths: {extra}")

index = index_entries()
modules = gitmodules()
modules_by_path: dict[str, tuple[str, dict[str, str]]] = {}
for name, cfg in modules.items():
    path = cfg.get("path")
    if not path:
        errors.append(f".gitmodules entry {name!r} has no path")
        continue
    if path in modules_by_path:
        errors.append(f"duplicate .gitmodules path: {path}")
    modules_by_path[path] = (name, cfg)

for path, authority in sorted(expected.items()):
    raw = ledger_by_path.get(path)
    if raw is None:
        continue

    for field in ("org", "scenario", "repo", "stack", "path", "branch", "repository"):
        if raw.get(field) != authority[field]:
            errors.append(
                f"{path}: ledger {field} {raw.get(field)!r} != {authority[field]!r}"
            )

    commit = raw.get("commit")
    if not isinstance(commit, str) or not SHA_RE.fullmatch(commit):
        errors.append(f"{path}: ledger commit is not a canonical 40-char SHA")
        continue

    indexed = index.get(path)
    if indexed is None:
        errors.append(f"{path}: missing from Git index")
    else:
        mode, sha = indexed
        if mode != "160000":
            errors.append(f"{path}: expected gitlink mode 160000, found {mode}")
        if sha != commit:
            errors.append(f"{path}: index gitlink {sha} != ledger commit {commit}")

    module = modules_by_path.get(path)
    if module is None:
        errors.append(f"{path}: missing .gitmodules entry")
        continue
    name, cfg = module
    if name != authority["module_name"]:
        errors.append(
            f"{path}: submodule name {name!r} != {authority['module_name']!r}"
        )
    if cfg.get("url") != authority["url"]:
        errors.append(f"{path}: submodule URL {cfg.get('url')!r} != {authority['url']!r}")
    if cfg.get("branch") != authority["branch"]:
        errors.append(
            f"{path}: submodule branch {cfg.get('branch')!r} != {authority['branch']!r}"
        )

governed_gitlinks = {
    path
    for path, (mode, _sha) in index.items()
    if mode == "160000"
    and path.startswith("stacks/")
    and "/projects/" in path
    and "/repos/" in path
}
if governed_gitlinks != set(expected):
    missing = sorted(set(expected) - governed_gitlinks)
    extra = sorted(governed_gitlinks - set(expected))
    if missing:
        errors.append(f"Git index missing governed gitlinks: {missing}")
    if extra:
        errors.append(f"Git index has unexpected governed gitlinks: {extra}")

governed_modules = {
    path
    for path in modules_by_path
    if path.startswith("stacks/") and "/projects/" in path and "/repos/" in path
}
if governed_modules != set(expected):
    missing = sorted(set(expected) - governed_modules)
    extra = sorted(governed_modules - set(expected))
    if missing:
        errors.append(f".gitmodules missing governed paths: {missing}")
    if extra:
        errors.append(f".gitmodules has unexpected governed paths: {extra}")

for spec in matrix:
    readme = f"stacks/{spec.stack}/projects/{spec.scenario}/repos/readme.md"
    indexed = index.get(readme)
    if indexed is None:
        errors.append(f"{readme}: missing organization-envelope README")
    elif indexed[0] == "160000":
        errors.append(f"{readme}: README must remain a superproject file, not a gitlink")

if errors:
    print("dummy-org gitlink verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print(
    "dummy-org gitlink verification OK: "
    f"{len(expected)} gitlinks, {len(projects)} org mirrors, {len(stacks)} stack branches"
)
