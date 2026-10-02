#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FLEET = ROOT / "shared/dummy-org-fleet.json"
STATE = ROOT / "shared/runtime-fixture-submodules.json"
RENDERER = ROOT / "scripts/render_runtime_fixture_gitmodules.py"
STATE_SCHEMA = "ores.comparisons.runtime-fixture-submodules/v1"


def repo_family(org: str, extension: str, roles: list[str]) -> list[str]:
    return [".github"] + [
        f"{org}-" + role.replace("{server_extension}", extension)
        for role in roles
    ]


def resolve_head(org: str, repo: str, branch: str) -> str:
    url = f"https://github.com/{org}/{repo}.git"
    result = subprocess.run(
        ["git", "ls-remote", "--exit-code", "--heads", url, f"refs/heads/{branch}"],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or "branch was not returned"
        raise RuntimeError(f"{org}/{repo}@{branch}: {detail}")
    rows = [line.split() for line in result.stdout.splitlines() if line.strip()]
    if len(rows) != 1 or len(rows[0]) < 2:
        raise RuntimeError(f"{org}/{repo}@{branch}: expected exactly one branch head")
    sha = rows[0][0]
    if len(sha) != 40 or any(ch not in "0123456789abcdef" for ch in sha):
        raise RuntimeError(f"{org}/{repo}@{branch}: non-canonical SHA {sha!r}")
    return sha


def build_plan() -> tuple[list[dict[str, str]], list[str]]:
    fleet = json.loads(FLEET.read_text())
    roles = fleet["runtime_fixture_policy"]["repository_family"]["roles"]
    fixtures = fleet["runtime_fixture_orgs"]
    pins: list[dict[str, str]] = []
    errors: list[str] = []

    for item in fixtures:
        org = item["org"]
        stack = item["stack"]
        slug = org.removeprefix("ores-dummy-org-")
        branch = item.get("default_branch", "main")
        repos = repo_family(org, item["server_extension"], roles)
        if len(repos) != 20 or len(set(repos)) != 20:
            errors.append(f"{org}: expected exactly 20 unique repos including .github")
            continue
        for repo in sorted(repos):
            try:
                sha = resolve_head(org, repo, branch)
            except RuntimeError as exc:
                errors.append(str(exc))
                continue
            pins.append(
                {
                    "org": org,
                    "stack": stack,
                    "slug": slug,
                    "repo": repo,
                    "branch": branch,
                    "sha": sha,
                    "path": f"stacks/{stack}/projects/{slug}/repos/{repo}",
                }
            )

    return pins, errors


def write_state(orgs: list[str]) -> None:
    payload = {
        "schema": STATE_SCHEMA,
        "complete_orgs": sorted(orgs),
        "partial_orgs": {},
        "pending_orgs": [],
        "notes": (
            f"All {len(orgs)} configured runtime fixture orgs are fully materialized as private Git submodules. "
            "Each complete org has .github plus the full 19-repo ores-cli family; Git index "
            "gitlinks pin exact main-branch commits and .gitmodules records the authenticated "
            "private HTTPS transport."
        ),
    }
    STATE.write_text(json.dumps(payload, indent=2) + "\n")


def apply_plan(pins: list[dict[str, str]]) -> None:
    for pin in pins:
        subprocess.run(
            [
                "git",
                "update-index",
                "--add",
                "--cacheinfo",
                f"160000,{pin['sha']},{pin['path']}",
            ],
            cwd=ROOT,
            check=True,
        )

    write_state(sorted({pin["org"] for pin in pins}))
    subprocess.run(["python3", str(RENDERER)], cwd=ROOT, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Resolve and materialize all governed private runtime-fixture submodules"
    )
    parser.add_argument("--apply", action="store_true", help="write gitlinks/state/.gitmodules")
    args = parser.parse_args()

    pins, errors = build_plan()
    expected_orgs = len(fixtures)
    expected = expected_orgs * (len(roles) + 1)
    if errors:
        print("runtime fixture materialization FAILED")
        for error in errors:
            print(" -", error)
        return 1
    if len(pins) != expected:
        print(f"runtime fixture materialization FAILED: expected {expected} pins, found {len(pins)}")
        return 1

    orgs = sorted({pin["org"] for pin in pins})
    if len(orgs) != expected_orgs:
        print(f"runtime fixture materialization FAILED: expected {expected_orgs} orgs, found {len(orgs)}")
        return 1

    if args.apply:
        apply_plan(pins)
        print(f"runtime fixture materialization APPLIED: orgs={len(orgs)}, gitlinks={len(pins)}")
    else:
        print(f"runtime fixture materialization PLAN OK: orgs={len(orgs)}, gitlinks={len(pins)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
