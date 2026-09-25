#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

parser = argparse.ArgumentParser()
parser.add_argument("--results", default="benchmarks/results")
parser.add_argument("--output", default="benchmarks/results/matrix.md")
args = parser.parse_args()

results = ROOT / args.results
rows = []
for path in sorted(results.glob("benchmark-*.json")):
    try:
        rows.append(json.loads(path.read_text()))
    except Exception:
        continue

def ms(item: dict, key: str) -> str:
    value = item.get(key)
    return "—" if value is None else f"{value / 1000:.3f}"

def mib(item: dict, key: str) -> str:
    value = item.get(key)
    return "—" if value is None else f"{value / 1048576:.2f}"

def cost(item: dict) -> str:
    value = item.get("estimatedCostMicrousdPerMillion")
    return "—" if value is None else "$" + f"{value / 1_000_000:.6f}"

lines = [
    "# Benchmark matrix",
    "",
    "| Scenario | Stack | Samples | p50 ms | p95 ms | p99 ms | Cold ms | RSS MiB | Artifact MiB | Cost / 1M |",
    "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
]

for item in sorted(rows, key=lambda value: (value["scenario"], value["stack"], value["measuredAt"])):
    lines.append(
        f"| {item['scenario']} | {item['stack']} | {item['samples']} | "
        f"{ms(item, 'warmP50Micros')} | {ms(item, 'warmP95Micros')} | {ms(item, 'warmP99Micros')} | "
        f"{ms(item, 'coldStartMicros')} | {mib(item, 'memoryRssBytes')} | "
        f"{mib(item, 'artifactSizeBytes')} | {cost(item)} |"
    )

output = ROOT / args.output
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text("\n".join(lines) + "\n")
print(output)
