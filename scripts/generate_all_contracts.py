#!/usr/bin/env python3
from __future__ import annotations
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
check = "--check" in sys.argv[1:]
projects = sorted(p for p in (ROOT / "stacks").glob("*/projects/*")
                  if (p / "contracts/projection.json").is_file())
if len(projects) != 18:
    raise SystemExit(f"expected 18 contract projects, found {len(projects)}")
for project in projects:
    cmd = [sys.executable, str(ROOT / "scripts/generate_contracts.py"), str(project)]
    if check:
        cmd.append("--check")
    subprocess.run(cmd, check=True)
print(f"contract projections OK for {len(projects)} projects")
