#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FLEET_PATH = ROOT / "shared/dummy-org-fleet.json"


def render_repos(policy: dict, entry: dict) -> list[str]:
    family = policy["repository_family"]
    return [
        family["name_template"].format(
            org=entry["org"],
            role=role.format(server_extension=entry["server_extension"]),
        )
        for role in family["roles"]
    ]


def main() -> int:
    fleet = json.loads(FLEET_PATH.read_text())
    policy = fleet["runtime_fixture_policy"]
    failures: list[str] = []
    checked = 0

    for entry in fleet.get("runtime_fixture_orgs", []):
        branch = entry["default_branch"]
        org = entry["org"]
        for repo in render_repos(policy, entry):
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

    print(f"runtime fixture remote reachability OK: {checked} repositories")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
