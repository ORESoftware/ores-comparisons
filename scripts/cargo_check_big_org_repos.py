#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess

from project_matrix import ROOT, load_project_specs

checked = 0
errors: list[str] = []

for spec in load_project_specs():
    if spec.stack != "ores-stack" or not spec.scenario.startswith("big-org-example-"):
        continue
    for repo in sorted(spec.repos_path.iterdir()):
        contract_path = repo / "repo.contract.json"
        cargo = repo / "Cargo.toml"
        if not contract_path.is_file() or not cargo.is_file():
            continue
        contract = json.loads(contract_path.read_text())
        if contract.get("kind") not in {"service", "worker", "frontend"}:
            continue
        result = subprocess.run(
            ["cargo", "check", "--manifest-path", str(cargo)],
            cwd=repo,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        if result.returncode != 0:
            errors.append(f"{repo.relative_to(ROOT)}:\n{result.stdout}")
        checked += 1

if checked != 9:
    errors.append(f"expected to cargo-check 9 ORES Stack big-org repos, checked {checked}")

if errors:
    print("ORES Stack big-org cargo checks FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("ORES Stack big-org cargo checks OK: 9 repos")
