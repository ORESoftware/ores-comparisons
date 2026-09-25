#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys

from project_matrix import ROOT, contract_project_specs

check = "--check" in sys.argv[1:]
specs = contract_project_specs()
if not specs:
    raise SystemExit("project matrix contains no contract-enabled projects")

for spec in specs:
    project = spec.path
    projection = project / "contracts/projection.json"
    if not project.is_dir():
        raise SystemExit(f"matrix project does not exist: {project}")
    if not projection.is_file():
        raise SystemExit(f"matrix contract project is missing projection: {projection}")
    cmd = [sys.executable, str(ROOT / "scripts/generate_contracts.py"), str(project)]
    if check:
        cmd.append("--check")
    subprocess.run(cmd, check=True)

print(f"contract projections OK for {len(specs)} governed projects")
