#!/usr/bin/env python3
from __future__ import annotations

from project_matrix import ROOT, contract_project_specs
from projection_schema import load_projection

projects = [spec.shared_repo_path for spec in contract_project_specs()]
errors: list[str] = []
if not projects:
    errors.append("project matrix contains no contract-enabled projects")

for shared in projects:
    projection = shared / "contracts/projection.json"
    if not projection.is_file():
        errors.append(f"{shared.relative_to(ROOT)}: missing contracts/projection.json")
        continue
    try:
        load_projection(projection)
        print(f"projection contract OK: {shared.relative_to(ROOT)}")
    except Exception as exc:
        errors.append(f"{shared.relative_to(ROOT)}: {exc}")

if errors:
    print("projection contract verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print(
    f"projection contract verification OK: all {len(projects)} simulated .github repos "
    "have admitted projection files"
)
