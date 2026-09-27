#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = (
    ".github/workflows/verify.yml",
    ".github/workflows/big-org-runtime.yml",
    ".github/workflows/generated-artifacts.yml",
    ".github/workflows/contract-compatibility.yml",
    ".github/workflows/dummy-org-remote-reachability.yml",
    ".github/workflows/runtime-project.yml",
)
EXPECTED = (
    "CROSS_REPO_READ_TOKEN: "
    "${{ secrets.COMPARISON_REPO_READ_TOKEN || secrets['cross-repo-token'] "
    "|| secrets.ORES_CROSS_REPO_READ_TOKEN || secrets.TEST_FLEET_READ_TOKEN }}"
)
LEGACY = "COMPARISON_REPO_READ_TOKEN: ${{ secrets.COMPARISON_REPO_READ_TOKEN }}"

errors: list[str] = []
for relative in WORKFLOWS:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"{relative}: workflow is missing")
        continue
    source = path.read_text(encoding="utf-8")
    count = source.count(EXPECTED)
    if count != 1:
        errors.append(
            f"{relative}: expected exactly one canonical CROSS_REPO_READ_TOKEN mapping, found {count}"
        )
    if LEGACY in source:
        errors.append(f"{relative}: legacy single-secret credential mapping remains")
    if relative != ".github/workflows/runtime-project.yml" and "CROSS_REPO_READ_TOKEN" not in source:
        errors.append(f"{relative}: resolved credential is never consumed")

if errors:
    print("private authority credential contract FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print(
    "private authority credential contract OK: "
    f"{len(WORKFLOWS)} workflows share one fail-closed resolution order"
)
