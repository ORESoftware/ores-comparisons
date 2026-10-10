#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FLEET = ROOT / "shared/dummy-org-fleet.json"
STATE = ROOT / "shared/runtime-fixture-submodules.json"
GITMODULES = ROOT / ".gitmodules"


def repo_family(org: str, extension: str, roles: list[str]) -> list[str]:
    return [".github"] + [
        f"{org}-" + role.replace("{server_extension}", extension)
        for role in roles
    ]


def strip_runtime_sections(source: str) -> str:
    lines = source.splitlines(keepends=True)
    output: list[str] = []
    skip = False
    for line in lines:
        if line.startswith("[submodule "):
            # Both generated fixture families are derived from reviewed ledgers.
            # Strip both before regenerating, so repeated renders never duplicate.
            skip = line.startswith(('[submodule "runtime--', '[submodule "oreslang-portability--'))
        if not skip:
            output.append(line)
    return "".join(output).rstrip() + "\n"


def render() -> str:
    fleet = json.loads(FLEET.read_text())
    state = json.loads(STATE.read_text())
    entries = {item["org"]: item for item in fleet["runtime_fixture_orgs"]}
    roles = fleet["runtime_fixture_policy"]["repository_family"]["roles"]

    selected: list[tuple[dict, list[str]]] = []
    for org in state["complete_orgs"]:
        item = entries[org]
        selected.append((item, repo_family(org, item["server_extension"], roles)))
    for org, repos in state["partial_orgs"].items():
        selected.append((entries[org], list(repos)))

    sections: list[str] = []
    for item, repos in selected:
        org = item["org"]
        stack = item["stack"]
        slug = org.removeprefix("ores-dummy-org-")
        branch = item.get("default_branch", "main")
        for repo in sorted(repos):
            safe = re.sub(r"[^A-Za-z0-9_-]+", "_", repo)
            path = f"stacks/{stack}/projects/{slug}/repos/{repo}"
            sections.append(
                f'[submodule "runtime--{stack}--{slug}--{safe}"]\n'
                f"\tpath = {path}\n"
                f"\turl = https://github.com/{org}/{repo}.git\n"
                f"\tbranch = {branch}\n"
            )

    # Cross-stack Oreslang portability uses private, immutable source Gitlinks
    # rather than synthetic runtime-fixture projects. It must be deterministic
    # and never reinterpret its pinned snapshots as executable runtime proof.
    portability = json.loads((ROOT / "shared/oreslang-portability.json").read_text())
    for target in portability["targets"]:
        stack = target["stack"]
        for pin in target["pins"]:
            repo = pin["repository"]
            path = f"stacks/{stack}/projects/oreslang-portability/repos/{repo}"
            if pin["path"] != path:
                raise ValueError(f"noncanonical Oreslang portability path: {pin['path']!r}")
            source = f"ores-dummy-org-oreslang-stack/{repo}"
            sections.append(
                f'\n[submodule "oreslang-portability--{stack}--{repo}"]\n'
                f"\tpath = {path}\n"
                f"\turl = https://github.com/{source}.git\n"
                f"\tbranch = main\n"
            )

    base = strip_runtime_sections(GITMODULES.read_text())
    return base + "".join(sections)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    rendered = render()
    current = GITMODULES.read_text()
    if args.check:
        if current != rendered:
            raise SystemExit(".gitmodules runtime fixture projection is stale")
        print("runtime fixture .gitmodules projection OK")
        return 0
    GITMODULES.write_text(rendered)
    print("rendered runtime fixture submodules into .gitmodules")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
