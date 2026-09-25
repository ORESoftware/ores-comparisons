#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "governance/github-actions.lock.json"
WORKFLOWS = ROOT / ".github/workflows"
SHA40 = re.compile(r"^[0-9a-f]{40}$")
USES = re.compile(r"^\s*-?\s*uses:\s*([^@\s]+)@([^\s#]+)")

lock = json.loads(LOCK.read_text())
if lock.get("schema") != "ores.comparisons.github-actions-lock/v1":
    raise SystemExit("unsupported GitHub Actions lock schema")
actions = lock.get("actions", {})
if not isinstance(actions, dict) or not actions:
    raise SystemExit("GitHub Actions lock contains no actions")

errors: list[str] = []
seen: set[str] = set()

for name, entry in sorted(actions.items()):
    sha = entry.get("sha") if isinstance(entry, dict) else None
    if not isinstance(sha, str) or not SHA40.fullmatch(sha):
        errors.append(f"lock entry {name} has invalid commit SHA {sha!r}")

for workflow in sorted(WORKFLOWS.glob("*.y*ml")):
    for line_number, line in enumerate(workflow.read_text().splitlines(), 1):
        match = USES.match(line)
        if not match:
            continue
        action, ref = match.groups()
        if action.startswith("./") or action.startswith("docker://"):
            continue
        seen.add(action)
        entry = actions.get(action)
        location = f"{workflow.relative_to(ROOT)}:{line_number}"
        if entry is None:
            errors.append(f"{location}: remote action {action} is not present in governance/github-actions.lock.json")
            continue
        expected = entry.get("sha")
        if ref != expected:
            errors.append(
                f"{location}: {action} must use immutable {expected}, found {ref}; "
                f"upstream tracking ref is {entry.get('upstream_ref')!r}"
            )
        if not SHA40.fullmatch(ref):
            errors.append(f"{location}: mutable/non-SHA action ref {action}@{ref}")

unused = sorted(set(actions) - seen)
if unused:
    errors.append(f"lock entries are unused by workflows: {unused}")

if errors:
    print("GitHub Actions pin verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print(f"GitHub Actions pin verification OK: {len(seen)} remote actions pinned by full commit SHA")
