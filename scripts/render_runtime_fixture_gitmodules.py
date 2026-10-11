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


def render_oreslang_modules(portability: dict, catalog: dict) -> str:
    """Render admitted source pins, rejecting manifest-driven .gitmodules injection."""
    allowed_sources = {
        "ores-dummy-org-oreslang-stack-api-server.ores",
        "ores-dummy-org-oreslang-stack-web-server.ores",
    }
    if portability.get("fixture_organization") != "ores-dummy-org-oreslang-stack":
        raise ValueError("unreviewed Oreslang fixture organization")
    sources = portability.get("fixture_repositories")
    if not isinstance(sources, list) or len(sources) != 2:
        raise ValueError("Oreslang requires exactly two audited source repositories")
    source_map = {}
    for row in sources:
        if not isinstance(row, dict) or set(row) != {"name", "branch", "sha"}:
            raise ValueError("invalid Oreslang source identity keys")
        name, branch, sha = row["name"], row["branch"], row["sha"]
        if name not in allowed_sources or name in source_map or branch != "main":
            raise ValueError("unapproved Oreslang source repository or default branch")
        if not isinstance(sha, str) or re.fullmatch(r"[a-f0-9]{40}", sha) is None:
            raise ValueError("Oreslang source SHA must be an immutable 40-hex revision")
        source_map[name] = sha
    if set(source_map) != allowed_sources:
        raise ValueError("Oreslang source roster incomplete")

    stack_ids = {entry["id"] for entry in catalog.get("stacks", [])}
    targets = portability.get("targets")
    if not isinstance(targets, list):
        raise ValueError("Oreslang portability targets must be an array")
    seen_stacks = set()
    sections = []
    for target in targets:
        if not isinstance(target, dict) or set(target) != {"stack", "status", "pins"}:
            raise ValueError("invalid Oreslang portability target keys")
        stack, status, pins = target["stack"], target["status"], target["pins"]
        if not isinstance(stack, str) or stack not in stack_ids or stack in seen_stacks:
            raise ValueError("unknown or duplicate Oreslang portability stack")
        seen_stacks.add(stack)
        if not isinstance(pins, list) or status not in {"pending", "partial", "complete"}:
            raise ValueError("invalid Oreslang portability target state")
        count = {"pending": 0, "partial": 1, "complete": 2}[status]
        if len(pins) != count:
            raise ValueError("Oreslang portability status/pin count mismatch")
        seen_names = set()
        for pin in pins:
            if not isinstance(pin, dict) or set(pin) != {"repository", "path", "sha"}:
                raise ValueError("invalid Oreslang Gitlink metadata")
            name = pin["repository"]
            if not isinstance(name, str) or name not in source_map or name in seen_names:
                raise ValueError("unexpected or duplicate Oreslang Gitlink source")
            seen_names.add(name)
            path = f"stacks/{stack}/projects/oreslang-portability/repos/{name}"
            if pin["path"] != path or pin["sha"] != source_map[name]:
                raise ValueError("Oreslang Gitlink path or source revision drift")
            sections.append(
                f'\n[submodule "oreslang-portability--{stack}--{name}"]\n'
                f"\tpath = {path}\n"
                f"\turl = https://github.com/ores-dummy-org-oreslang-stack/{name}.git\n"
                f"\tbranch = main\n"
            )
        if status == "complete" and seen_names != allowed_sources:
            raise ValueError("incomplete Oreslang source pair")
    if seen_stacks != stack_ids:
        raise ValueError("Oreslang target inventory differs from registered stack catalog")
    return "".join(sections)


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

    # This ledger is source-only. It does not promote any runtime or FaaS.
    portability = json.loads((ROOT / "shared/oreslang-portability.json").read_text())
    catalog = json.loads((ROOT / "shared/stack-catalog.json").read_text())
    sections.append(render_oreslang_modules(portability, catalog))

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
