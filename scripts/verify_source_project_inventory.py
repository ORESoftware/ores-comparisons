#!/usr/bin/env python3
"""Fail-closed native source repository mapping; remote mode checks completeness.

Inventory presence is NOT fixture materialization, a successful build, or runtime proof.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "shared/source-project-inventory.json"
CATALOG = ROOT / "shared/stack-catalog.json"
SCHEMA = "ores.comparisons.source-project-inventory/v1"
REQUIRED = {"litegraph", "ores-stack"}
CATEGORIES = {
    "applications", "developer-tooling", "governance", "infrastructure",
    "interfaces", "runtime", "service",
}
REPO_PART = re.compile(r"^[A-Za-z0-9_.-]+$")
BRANCH = re.compile(r"^[A-Za-z0-9_.\-/]+$")


def validate(catalog: dict, inventory: dict) -> list[str]:
    errors: list[str] = []
    if inventory.get("schema") != SCHEMA:
        errors.append("unexpected inventory schema")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(inventory.get("snapshot_date", ""))):
        errors.append("snapshot_date must use YYYY-MM-DD")
    if catalog.get("schema") != "ores.comparisons.stack-catalog/v1":
        errors.append("unexpected stack catalog schema")
    entries = catalog.get("stacks")
    if not isinstance(entries, list):
        return errors + ["stack catalog entries must be an array"]
    catalog_by_id = {
        entry.get("id"): entry for entry in entries
        if isinstance(entry, dict) and isinstance(entry.get("id"), str)
    }
    stacks = inventory.get("stacks")
    if not isinstance(stacks, list):
        return errors + ["inventory stacks must be an array"]
    seen_stacks: set[str] = set()
    seen_repos: set[str] = set()
    for stack in stacks:
        if not isinstance(stack, dict):
            errors.append("stack entry must be an object")
            continue
        stack_id, owner = stack.get("stack_id"), stack.get("github_owner")
        if not isinstance(stack_id, str) or not isinstance(owner, str):
            errors.append("stack_id and github_owner must be strings")
            continue
        if stack_id in seen_stacks:
            errors.append(f"duplicate stack {stack_id}")
        seen_stacks.add(stack_id)
        canonical = catalog_by_id.get(stack_id)
        if canonical is None:
            errors.append(f"{stack_id}: not in stack catalog (unassigned stack)")
        elif canonical.get("github_owner") != owner:
            errors.append(f"{stack_id}: owner {owner!r} != catalog {canonical.get('github_owner')!r}")
        if not REPO_PART.fullmatch(owner) or owner in {".", ".."}:
            errors.append(f"{stack_id}: invalid owner")
            continue
        repositories = stack.get("repositories")
        if not isinstance(repositories, list):
            errors.append(f"{stack_id}: repositories must be an array")
            continue
        minimum = stack.get("minimum_repositories")
        if type(minimum) is not int or minimum <= 0 or len(repositories) < minimum:
            errors.append(f"{stack_id}: missing minimum native source repositories")
        names: list[str] = []
        for item in repositories:
            if not isinstance(item, dict):
                errors.append(f"{stack_id}: repository entry must be an object")
                continue
            name = item.get("repository")
            if not isinstance(name, str):
                errors.append(f"{stack_id}: repository name must be a string")
                continue
            names.append(name)
            if name in seen_repos:
                errors.append(f"duplicate repository assignment {name}")
            seen_repos.add(name)
            if not name.startswith(owner + "/"):
                errors.append(f"{name}: repository belongs to another stack owner")
            basename = name.removeprefix(owner + "/")
            if not REPO_PART.fullmatch(basename) or basename in {".", ".."}:
                errors.append(f"{name}: invalid repository path")
            if item.get("category") not in CATEGORIES:
                errors.append(f"{name}: unknown source project category")
            if item.get("visibility") not in {"public", "private", "internal"}:
                errors.append(f"{name}: invalid visibility")
            ref = item.get("default_branch")
            if not isinstance(ref, str) or not BRANCH.fullmatch(ref) or ".." in ref:
                errors.append(f"{name}: invalid default branch")
        if names != sorted(names):
            errors.append(f"{stack_id}: repository inventory must be lexically sorted")
    if seen_stacks != REQUIRED:
        errors.append(f"expected scoped stacks {sorted(REQUIRED)}, got {sorted(seen_stacks)}")
    return errors


def compare_remote(inventory: dict, observed: dict[str, list[dict]]) -> list[str]:
    errors: list[str] = []
    for stack in inventory["stacks"]:
        owner = stack["github_owner"]
        expected = {item["repository"]: item for item in stack["repositories"]}
        actual: dict[str, dict] = {}
        for item in observed.get(owner, []):
            name = item.get("full_name")
            if not isinstance(name, str) or not name.startswith(owner + "/"):
                errors.append(f"{owner}: invalid remote repository identity {name!r}")
                continue
            if name in actual:
                errors.append(f"{owner}: duplicate remote repository {name}")
            actual[name] = item
        for name in sorted(set(actual) - set(expected)):
            errors.append(f"UNASSIGNED SOURCE PROJECT {name}: register under {stack['stack_id']} or document an explicit exclusion")
        for name in sorted(set(expected) - set(actual)):
            errors.append(f"MISSING REMOTE PROJECT {name}: inventory is stale or credential lacks private-repo visibility")
        for name in sorted(set(expected) & set(actual)):
            recorded = expected[name]
            remote = actual[name]
            for field in ("default_branch", "visibility"):
                if recorded[field] != remote.get(field):
                    errors.append(f"{name}: {field} drift, recorded={recorded[field]!r}, remote={remote.get(field)!r}")
    return errors


def fetch_owner_repositories(owner: str, token: str) -> list[dict]:
    repositories: list[dict] = []
    for page in range(1, 51):
        url = f"https://api.github.com/orgs/{owner}/repos?type=all&per_page=100&page={page}"
        request = Request(
            url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "ores-comparisons-source-audit",
            },
        )
        with urlopen(request, timeout=20) as response:
            batch = json.load(response)
        if not isinstance(batch, list):
            raise ValueError(f"{owner}: expected an array from GitHub repository enumeration")
        repositories.extend(batch)
        if len(batch) < 100:
            return repositories
    raise ValueError(f"{owner}: repository enumeration exceeded 50 pages; refuse partial evidence")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--remote", action="store_true", help="requires dedicated cross-org metadata token; compare GitHub exhaustive source repos")
    parser.add_argument("--token-env", default="SOURCE_PROJECT_AUDIT_TOKEN")
    args = parser.parse_args()
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
    errors = validate(catalog, inventory)
    if args.remote and not errors:
        token = os.environ.get(args.token_env, "")
        if not token:
            errors.append(f"remote audit requires {args.token_env}; no remote coverage was checked")
        else:
            try:
                observed = {
                    stack["github_owner"]: fetch_owner_repositories(stack["github_owner"], token)
                    for stack in inventory["stacks"]
                }
                errors.extend(compare_remote(inventory, observed))
            except (HTTPError, URLError, ValueError) as exc:
                errors.append(f"remote inventory audit failed: {type(exc).__name__}: {exc}")
    if errors:
        print("source project inventory FAILED")
        for error in errors:
            print(" -", error)
        return 1
    count = sum(len(stack["repositories"]) for stack in inventory["stacks"])
    proof = "remote owner enumeration confirmed" if args.remote else "offline registration only, remote enumeration NOT checked"
    print(f"source project inventory OK: {count} source repositories in {len(inventory['stacks'])} stacks; {proof}; not runtime proof")
    return 0


if __name__ == "__main__":
    sys.exit(main())
