#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FLEET_PATH = ROOT / "shared/dummy-org-fleet.json"
STATE_PATH = ROOT / "shared/runtime-fixture-submodules.json"


def render_repos(policy: dict, entry: dict) -> list[str]:
    family = policy["repository_family"]
    return [".github"] + [
        family["name_template"].format(
            org=entry["org"],
            role=role.format(server_extension=entry["server_extension"]),
        )
        for role in family["roles"]
    ]


def main() -> int:
    fleet = json.loads(FLEET_PATH.read_text())
    state = json.loads(STATE_PATH.read_text())
    policy = fleet["runtime_fixture_policy"]
    entries = {item["org"]: item for item in fleet.get("runtime_fixture_orgs", [])}
    failures: list[str] = []
    checked = 0

    selected: list[tuple[dict, list[str]]] = []
    for org in state.get("complete_orgs", []):
        entry = entries.get(org)
        if entry is None:
            failures.append(f"unknown complete org in state: {org}")
            continue
        selected.append((entry, render_repos(policy, entry)))
    for org, repos in state.get("partial_orgs", {}).items():
        entry = entries.get(org)
        if entry is None:
            failures.append(f"unknown partial org in state: {org}")
            continue
        selected.append((entry, list(repos)))

    for entry, repos in selected:
        branch = entry["default_branch"]
        org = entry["org"]
        for repo in repos:
            url = f"https://github.com/{org}/{repo}.git"
            result = subprocess.run(
                ["git", "ls-remote", "--exit-code", "--heads", url, f"refs/heads/{branch}"],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            checked += 1
            if result.returncode != 0 or not result.stdout.strip():
                detail = result.stderr.strip() or "branch was not returned"
                failures.append(f"{org}/{repo}@{branch}: {detail}")

    if failures:
        print("runtime fixture remote reachability FAILED")
        for failure in failures:
            print(f" - {failure}")
        return 1

    pending = state.get("pending_orgs", [])
    print(
        f"runtime fixture remote reachability OK: {checked} materialized repositories; "
        f"pending_orgs={len(pending)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
