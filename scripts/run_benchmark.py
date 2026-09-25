#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import time
import urllib.request
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STACKS = {"beamscale", "scintilla-run", "ores-stack"}
SCENARIOS = {"http-observability", "forms-chat-workflow", "cached-rpc"}

def percentile(values: list[int], p: float) -> int:
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(p * len(ordered)) - 1))
    return ordered[index]

def request_once(url: str, timeout: float) -> tuple[int, int]:
    start = time.perf_counter_ns()
    with urllib.request.urlopen(url, timeout=timeout) as response:
        response.read()
        status = response.status
    micros = (time.perf_counter_ns() - start) // 1000
    return status, micros

def rss_bytes(pid: int) -> int:
    output = subprocess.check_output(
        ["ps", "-o", "rss=", "-p", str(pid)],
        text=True,
    ).strip()
    return int(output) * 1024

def artifact_size(path: Path) -> int:
    if path.is_file():
        return path.stat().st_size
    total = 0
    for child in path.rglob("*"):
        if child.is_file() and not child.is_symlink():
            total += child.stat().st_size
    return total

def revision() -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(ROOT), "rev-parse", "HEAD"],
            text=True,
        ).strip()
    except Exception:
        return "unknown"

parser = argparse.ArgumentParser()
parser.add_argument("--stack", required=True, choices=sorted(STACKS))
parser.add_argument("--scenario", required=True, choices=sorted(SCENARIOS))
parser.add_argument("--url", required=True)
parser.add_argument("--source", choices=["local", "remote"], default="local")
parser.add_argument("--requests", type=int, default=50)
parser.add_argument("--warmup", type=int, default=5)
parser.add_argument("--timeout", type=float, default=10.0)
parser.add_argument("--cold-start-micros", type=int)
parser.add_argument("--pid", type=int)
parser.add_argument("--artifact")
parser.add_argument("--cost-per-million-usd")
parser.add_argument("--output")
args = parser.parse_args()

if args.requests < 1 or args.warmup < 0:
    parser.error("requests must be >= 1 and warmup must be >= 0")

for _ in range(args.warmup):
    status, _ = request_once(args.url, args.timeout)
    if not 200 <= status < 400:
        raise SystemExit(f"warmup HTTP status {status}")

latencies: list[int] = []
last_status = 0
for _ in range(args.requests):
    last_status, micros = request_once(args.url, args.timeout)
    if not 200 <= last_status < 400:
        raise SystemExit(f"benchmark HTTP status {last_status}")
    latencies.append(micros)

rev = revision()
result = {
    "id": f"benchmark:{args.stack}:{args.scenario}:{rev}:{int(time.time())}",
    "stack": args.stack,
    "scenario": args.scenario,
    "revision": rev,
    "source": args.source,
    "measuredAt": datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
    "samples": len(latencies),
    "httpStatus": last_status,
    "warmP50Micros": percentile(latencies, 0.50),
    "warmP95Micros": percentile(latencies, 0.95),
    "warmP99Micros": percentile(latencies, 0.99),
}

if args.cold_start_micros is not None:
    result["coldStartMicros"] = args.cold_start_micros
if args.pid is not None:
    result["memoryRssBytes"] = rss_bytes(args.pid)
if args.artifact is not None:
    artifact = Path(args.artifact)
    if not artifact.is_absolute():
        artifact = (ROOT / artifact).resolve()
    if not artifact.exists():
        raise SystemExit(f"artifact path does not exist: {artifact}")
    result["artifactSizeBytes"] = artifact_size(artifact)
if args.cost_per_million_usd is not None:
    value = (
        Decimal(args.cost_per_million_usd) * Decimal(1_000_000)
    ).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    result["estimatedCostMicrousdPerMillion"] = int(value)

output = (
    Path(args.output)
    if args.output
    else ROOT
    / "benchmarks/results"
    / f"benchmark-{args.stack}-{args.scenario}-{int(time.time())}.json"
)
if not output.is_absolute():
    output = ROOT / output
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(result, indent=2) + "\n")
print(output)
