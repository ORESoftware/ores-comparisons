#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FLEET_PATH = ROOT / "shared/dummy-org-fleet.json"
SCENARIO_PATH = ROOT / "shared/dummy-org-map.json"
CATALOG_PATH = ROOT / "shared/stack-catalog.json"

FLEET_SCHEMA = "ores.comparisons.dummy-org-fleet/v1"
SCENARIO_SCHEMA = "ores.comparisons.dummy-org-map/v1"

ALLOWED_SOURCE_TARGETS = {
    ("clojure", "jvm"): ".clj",
    ("java", "jvm"): ".java",
    ("ruby", "graal"): ".rb",
    ("gleam", "js"): ".gleam",
    ("typescript", "js"): ".ts",
    ("rust", "wasm"): ".rs",
    ("zig", "wasm"): ".zig",
    ("pony", "native"): ".pony",
    ("rust", "native"): ".rs",
    ("rust", "gpu"): ".rs",
    ("cuda", "gpu"): ".cu",
}

ORG_RE = re.compile(
    r"^ores-dummy-org-(?P<source>[a-z0-9]+)-(?P<target>[a-z0-9]+)-(?P<index>[1-9][0-9]*)$"
)


def load(path: Path):
    return json.loads(path.read_text())


def render_repos(policy: dict, entry: dict) -> list[str]:
    family = policy["repository_family"]
    org = entry["org"]
    extension = entry["server_extension"]
    return [".github"] + [
        family["name_template"].format(
            org=org,
            role=role.format(server_extension=extension),
        )
        for role in family["roles"]
    ]


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def main() -> int:
    errors: list[str] = []
    fleet = load(FLEET_PATH)
    scenario = load(SCENARIO_PATH)
    catalog = load(CATALOG_PATH)

    if fleet.get("schema") != FLEET_SCHEMA:
        fail(errors, f"unexpected fleet schema: {fleet.get('schema')!r}")
    if scenario.get("schema") != SCENARIO_SCHEMA:
        fail(errors, f"unexpected scenario schema: {scenario.get('schema')!r}")
    if fleet.get("scenario_authority") != "shared/dummy-org-map.json":
        fail(errors, "scenario_authority must be shared/dummy-org-map.json")

    scenario_expected = {
        name: (entry["org"], sorted(entry["repos"]))
        for name, entry in scenario.get("projects", {}).items()
    }
    scenario_actual = {
        entry["scenario"]: (entry["org"], sorted(entry["repos"]))
        for entry in fleet.get("scenario_orgs", [])
    }
    if scenario_actual != scenario_expected:
        fail(errors, "scenario_orgs diverge from shared/dummy-org-map.json")

    fixture_orgs = fleet.get("runtime_fixture_orgs", [])
    if not isinstance(fixture_orgs, list):
        fail(errors, "runtime_fixture_orgs must be an array")
        fixture_orgs = []

    org_names = [entry.get("org") for entry in fixture_orgs]
    if len(org_names) != len(set(org_names)):
        fail(errors, "runtime fixture org names must be unique")

    all_org_names = {entry["org"] for entry in fleet.get("scenario_orgs", [])} | set(org_names)
    expected_orgs = len(fleet.get("scenario_orgs", [])) + len(fixture_orgs)
    if len(all_org_names) != expected_orgs:
        fail(
            errors,
            f"dummy-org fleet must contain {expected_orgs} unique orgs, found {len(all_org_names)}",
        )

    policy = fleet.get("runtime_fixture_policy", {})
    dedicated = set(policy.get("dedicated_stacks", []))
    minimum = policy.get("minimum_orgs_per_dedicated_stack")
    if minimum != 2:
        fail(errors, "minimum_orgs_per_dedicated_stack must remain 2")
    if policy.get("private_repo_transport") != "git-submodule":
        fail(errors, "private_repo_transport must be git-submodule")

    family = policy.get("repository_family", {})
    roles = family.get("roles", [])
    if family.get("count") != 20 or len(roles) != 19 or len(set(roles)) != 19:
        fail(errors, "runtime fixture repository family must contain .github plus exactly 19 unique role repos")
    if family.get("includes_dot_github") is not True:
        fail(errors, "runtime fixture repository family must explicitly include .github")
    if family.get("name_template") != "{org}-{role}":
        fail(errors, "runtime fixture repository name_template must be {org}-{role}")

    by_stack: dict[str, list[str]] = defaultdict(list)
    total_repos = 0

    for entry in fixture_orgs:
        org = entry.get("org")
        stack = entry.get("stack")
        source = entry.get("source_language")
        target = entry.get("artifact_target")
        ext = entry.get("server_extension")

        if not all(isinstance(x, str) and x for x in (org, stack, source, target, ext)):
            fail(errors, f"invalid runtime fixture entry: {entry!r}")
            continue

        match = ORG_RE.fullmatch(org)
        if not match:
            fail(errors, f"{org}: invalid source-target org name")
        elif match.group("source") != source or match.group("target") != target:
            fail(errors, f"{org}: org name does not match source/target metadata")

        if (source, target) == ("typescript", "wasm"):
            fail(errors, f"{org}: TypeScript -> WASM is forbidden")

        expected_ext = ALLOWED_SOURCE_TARGETS.get((source, target))
        if expected_ext is None:
            fail(errors, f"{org}: unsupported source-target pair {source}->{target}")
        elif ext != expected_ext:
            fail(errors, f"{org}: server_extension {ext!r} != {expected_ext!r}")

        if entry.get("default_branch") != "main":
            fail(errors, f"{org}: default_branch must be main")
        if entry.get("transport") != "git-submodule":
            fail(errors, f"{org}: private runtime fixtures must use git-submodule transport")

        rendered = render_repos(policy, entry)
        if len(rendered) != 20 or len(set(rendered)) != 20:
            fail(errors, f"{org}: rendered repository family is not exactly 20 unique repos")
        for repo in rendered:
            if repo != ".github" and not repo.startswith(org + "-"):
                fail(errors, f"{org}: repository escaped org prefix: {repo}")
        total_repos += len(rendered)
        by_stack[stack].append(org)

    for stack in sorted(dedicated):
        if len(by_stack.get(stack, [])) < minimum:
            fail(errors, f"{stack}: requires at least {minimum} runtime fixture orgs")

    unexpected_stacks = set(by_stack) - dedicated
    if unexpected_stacks:
        fail(errors, f"fixture orgs assigned to non-dedicated stacks: {sorted(unexpected_stacks)}")

    catalog_entries = {
        entry.get("id"): entry for entry in catalog.get("stacks", []) if isinstance(entry, dict)
    }
    for stack, entry in catalog_entries.items():
        if entry.get("topology_status") != "materialized":
            fail(errors, f"{stack}: topology_status must be materialized")
        declared = entry.get("runtime_fixture_orgs", [])
        if sorted(declared) != sorted(by_stack.get(stack, [])):
            fail(errors, f"{stack}: stack-catalog runtime_fixture_orgs drift from fleet map")

    for stack in dedicated:
        if stack not in catalog_entries:
            fail(errors, f"{stack}: dedicated stack missing from stack catalog")

    if errors:
        print("runtime fixture org verification FAILED")
        for error in errors:
            print(f" - {error}")
        return 1

    print(
        "runtime fixture org verification OK: "
        f"{len(all_org_names)} orgs total, {len(fixture_orgs)} runtime fixture orgs, "
        f"{total_repos} governed runtime fixture repos"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
