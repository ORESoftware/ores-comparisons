#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
from projection_schema import load_projection

ROOT = Path(__file__).resolve().parents[1]
projects = sorted(
    p for p in ROOT.glob("stacks/*/projects/*")
    if p.is_dir() and (p / "contracts/projection.json").is_file()
)
errors: list[str] = []
if len(projects) != 9:
    errors.append(f"expected 9 project projections, found {len(projects)}")
for project in projects:
    try:
        load_projection(project / "contracts/projection.json")
        print(f"projection contract OK: {project.relative_to(ROOT)}")
    except Exception as exc:
        errors.append(f"{project.relative_to(ROOT)}: {exc}")
if errors:
    print("projection contract verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)
print("projection contract verification OK: all 9 live projection files are admitted")
