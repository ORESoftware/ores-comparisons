#!/usr/bin/env python3
from __future__ import annotations

import configparser
import json
import subprocess

from project_matrix import ROOT, load_materialization_specs

MAP_PATH = ROOT / "shared/dummy-org-map.json"
VERIFIED_LEDGER_PATH = ROOT / "shared/dummy-org-gitlinks.json"
MATERIALIZED_LEDGER_PATH = ROOT / "shared/materialized-dummy-org-gitlinks.json"
GITMODULES_PATH = ROOT / ".gitmodules"

errors: list[str] = []

try:
    specs = load_materialization_specs()
    mapping = json.loads(MAP_PATH.read_text())
    verified_ledger = json.loads(VERIFIED_LEDGER_PATH.read_text())
    materialized_ledger = json.loads(MATERIALIZED_LEDGER_PATH.read_text())
except Exception as exc:
    raise SystemExit(f"unable to load materialization topology: {exc}") from exc

if mapping.get("schema") != "ores.comparisons.dummy-org-map/v1":
    errors.append("dummy-org map schema drift")
if verified_ledger.get("schema") != "ores.comparisons.dummy-org-gitlinks/v1":
    errors.append("verified dummy-org gitlink ledger schema drift")
if materialized_ledger.get("schema") != "ores.comparisons.materialized-dummy-org-gitlinks/v1":
    errors.append("materialized dummy-org gitlink ledger schema drift")

stacks = mapping.get("stacks")
projects = mapping.get("projects")
if not isinstance(stacks, dict) or not stacks:
    errors.append("dummy-org map must contain non-empty stacks object")
    stacks = {}
if not isinstance(projects, dict) or not projects:
    errors.append("dummy-org map must contain non-empty projects object")
    projects = {}

expected: dict[str, dict[str, str]] = {}
for spec in specs:
    stack_info = stacks.get(spec.stack)
    project_info = projects.get(spec.scenario)
    if not isinstance(stack_info, dict):
        errors.append(f"{spec.stack}/{spec.scenario}: missing stack in dummy-org map")
        continue
    if not isinstance(project_info, dict):
        errors.append(f"{spec.stack}/{spec.scenario}: missing scenario in dummy-org map")
        continue
    branch = stack_info.get("branch")
    org = project_info.get("org")
    repos = project_info.get("repos")
    if branch != f"stack/{spec.stack}":
        errors.append(
            f"{spec.stack}: dummy-org branch must be stack/{spec.stack}, found {branch!r}"
        )
        continue
    if not isinstance(org, str) or not org:
        errors.append(f"{spec.scenario}: invalid dummy org")
        continue
    if not isinstance(repos, list) or not repos or not all(
        isinstance(repo, str) and repo for repo in repos
    ):
        errors.append(f"{spec.scenario}: invalid dummy repo list")
        continue
    for repo in repos:
        path = f"stacks/{spec.stack}/projects/{spec.scenario}/repos/{repo}"
        expected[path] = {
            "org": org,
            "scenario": spec.scenario,
            "repo": repo,
            "stack": spec.stack,
            "branch": branch,
            "repository": f"{org}/{repo}",
        }


def collect_verified_entries(raw: object) -> dict[str, dict[str, object]]:
    entries: dict[str, dict[str, object]] = {}
    if not isinstance(raw, list):
        errors.append("verified gitlink ledger must contain entries array")
        return entries
    for entry in raw:
        if not isinstance(entry, dict):
            errors.append("verified gitlink ledger contains non-object entry")
            continue
        path = entry.get("path")
        if not isinstance(path, str) or not path:
            errors.append("verified gitlink ledger contains entry with invalid path")
            continue
        if path in entries:
            errors.append(f"verified gitlink ledger contains duplicate path {path}")
            continue
        entries[path] = entry
    return entries


verified_entries = collect_verified_entries(verified_ledger.get("entries"))
materialized_stacks = materialized_ledger.get("stacks")
base_stack = materialized_ledger.get("base_stack")
if not isinstance(materialized_stacks, list) or not materialized_stacks or not all(
    isinstance(stack, str) and stack for stack in materialized_stacks
):
    errors.append("materialized ledger must declare a non-empty stacks array")
    materialized_stacks = []
if materialized_stacks != sorted(set(materialized_stacks), key=materialized_stacks.index):
    errors.append("materialized ledger stack ids must be unique")
if not isinstance(base_stack, str) or not base_stack:
    errors.append("materialized ledger must declare base_stack")
    base_stack = ""

materialized_stack_set = set(materialized_stacks)
if base_stack in materialized_stack_set:
    errors.append("base_stack cannot also be a topology-only materialized stack")
if not materialized_stack_set <= set(stacks):
    errors.append(
        f"materialized ledger references unknown stacks {sorted(materialized_stack_set - set(stacks))}"
    )

base_commits: dict[tuple[str, str], str] = {}
for entry in verified_entries.values():
    if entry.get("stack") != base_stack:
        continue
    scenario = entry.get("scenario")
    repo = entry.get("repo")
    commit = entry.get("commit")
    if (
        isinstance(scenario, str)
        and isinstance(repo, str)
        and isinstance(commit, str)
        and len(commit) == 40
    ):
        base_commits[(scenario, repo)] = commit

synthetic_entries: dict[str, dict[str, object]] = {}
for path, want in expected.items():
    stack = want["stack"]
    if stack not in materialized_stack_set:
        continue
    commit = base_commits.get((want["scenario"], want["repo"]))
    if commit is None:
        errors.append(
            f"{path}: no {base_stack} source commit for "
            f"{want['scenario']}/{want['repo']}"
        )
        continue
    synthetic_entries[path] = {**want, "commit": commit}

verified_expected_paths = {
    path for path, want in expected.items() if want["stack"] not in materialized_stack_set
}
if set(verified_entries) != verified_expected_paths:
    errors.append(
        "verified gitlink ledger coverage drift: "
        f"missing={sorted(verified_expected_paths - set(verified_entries))} "
        f"extra={sorted(set(verified_entries) - verified_expected_paths)}"
    )

ledger_entries = {**verified_entries, **synthetic_entries}
if set(ledger_entries) != set(expected):
    errors.append(
        "combined gitlink coverage drift: "
        f"missing={sorted(set(expected) - set(ledger_entries))} "
        f"extra={sorted(set(ledger_entries) - set(expected))}"
    )

for path, want in expected.items():
    entry = ledger_entries.get(path)
    if entry is None:
        continue
    for key, value in want.items():
        if entry.get(key) != value:
            errors.append(
                f"{path}: ledger {key}={entry.get(key)!r}, expected {value!r}"
            )
    commit = entry.get("commit")
    if not isinstance(commit, str) or len(commit) != 40 or any(
        ch not in "0123456789abcdef" for ch in commit
    ):
        errors.append(f"{path}: ledger has invalid commit SHA {commit!r}")

index = subprocess.run(
    ["git", "ls-files", "--stage"],
    cwd=ROOT,
    check=True,
    text=True,
    stdout=subprocess.PIPE,
).stdout
indexed_gitlinks: dict[str, str] = {}
for line in index.splitlines():
    metadata, path = line.split("\t", 1)
    mode, sha, _stage = metadata.split()
    if mode == "160000":
        indexed_gitlinks[path] = sha

if set(indexed_gitlinks) != set(expected):
    errors.append(
        "git index gitlink coverage drift: "
        f"missing={sorted(set(expected) - set(indexed_gitlinks))} "
        f"extra={sorted(set(indexed_gitlinks) - set(expected))}"
    )
for path, sha in indexed_gitlinks.items():
    entry = ledger_entries.get(path)
    if entry is not None and entry.get("commit") != sha:
        errors.append(
            f"{path}: index gitlink {sha} != ledger commit {entry.get('commit')}"
        )

parser = configparser.ConfigParser()
parser.read(GITMODULES_PATH)
modules: dict[str, dict[str, str]] = {}
for section in parser.sections():
    if not section.startswith('submodule "'):
        errors.append(f".gitmodules contains unexpected section {section}")
        continue
    path = parser.get(section, "path", fallback="")
    if not path:
        errors.append(f"{section}: missing path")
        continue
    if path in modules:
        errors.append(f".gitmodules contains duplicate path {path}")
        continue
    modules[path] = {
        "url": parser.get(section, "url", fallback=""),
        "branch": parser.get(section, "branch", fallback=""),
    }

if set(modules) != set(expected):
    errors.append(
        ".gitmodules coverage drift: "
        f"missing={sorted(set(expected) - set(modules))} "
        f"extra={sorted(set(modules) - set(expected))}"
    )

for path, want in expected.items():
    module = modules.get(path)
    if module is None:
        continue
    expected_url = f"https://github.com/{want['repository']}.git"
    if module["url"] != expected_url:
        errors.append(
            f"{path}: .gitmodules url={module['url']!r}, expected {expected_url!r}"
        )
    if module["branch"] != want["branch"]:
        errors.append(
            f"{path}: .gitmodules branch={module['branch']!r}, expected {want['branch']!r}"
        )

if errors:
    print("dummy-org gitlink verification FAILED")
    for error in errors:
        print(f" - {error}")
    raise SystemExit(1)

print(
    "dummy-org gitlink verification OK: "
    f"{len(expected)} gitlinks across {len(stacks)} materialized stacks, "
    f"{len(verified_entries)} runtime-verified + {len(synthetic_entries)} topology-only"
)
