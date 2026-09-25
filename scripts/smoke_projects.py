#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX = json.loads((ROOT / "benchmarks/matrix.json").read_text())

def revision() -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(ROOT), "rev-parse", "HEAD"],
            text=True,
        ).strip()
    except Exception:
        return "unknown"

def run(argv: list[str], cwd: Path) -> bool:
    print("+", " ".join(argv), f"(cwd={cwd.relative_to(ROOT)})")
    return subprocess.run(argv, cwd=cwd, check=False).returncode == 0

parser = argparse.ArgumentParser()
parser.add_argument("--check", action="store_true")
parser.add_argument("--execute", action="store_true")
parser.add_argument("--stack")
parser.add_argument("--scenario")
args = parser.parse_args()

if args.check == args.execute:
    parser.error("choose exactly one of --check or --execute")

subprocess.run(
    ["python3", str(ROOT / "scripts/verify_benchmark_matrix.py")],
    check=True,
)
if args.check:
    raise SystemExit(0)

results_dir = ROOT / "benchmarks/results"
results_dir.mkdir(parents=True, exist_ok=True)
failed = False
rev = revision()

for item in MATRIX["projects"]:
    if args.stack and item["stack"] != args.stack:
        continue
    if args.scenario and item["scenario"] != args.scenario:
        continue

    cwd = ROOT / item["path"]
    build_ok = True
    for argv in item["build"]:
        if not run(argv, cwd):
            build_ok = False
            break

    deploy_status = "skipped"
    note = None
    if build_ok and item["deploy_mode"] == "dry-run":
        deploy_ok = True
        for argv in item["deploy"]:
            if not run(argv, cwd):
                deploy_ok = False
                break
        deploy_status = "passed" if deploy_ok else "failed"
        failed = failed or not deploy_ok
    elif item["deploy_mode"] == "artifact-handoff":
        note = (
            "application repo has no remote infra target; "
            "build artifact is the deployment handoff boundary"
        )

    if not build_ok:
        failed = True

    receipt = {
        "id": f"smoke:{item['stack']}:{item['scenario']}:{rev}",
        "stack": item["stack"],
        "scenario": item["scenario"],
        "revision": rev,
        "buildStatus": "passed" if build_ok else "failed",
        "deployStatus": deploy_status,
        "deployMode": item["deploy_mode"],
    }
    if note:
        receipt["note"] = note

    output = results_dir / f"smoke-{item['stack']}-{item['scenario']}.json"
    output.write_text(json.dumps(receipt, indent=2) + "\n")
    print(output.relative_to(ROOT))

raise SystemExit(1 if failed else 0)
