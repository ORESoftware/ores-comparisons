#!/usr/bin/env python3
"""Generate/verify the immutable ORES Stack server inventory from checked-in authorities."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
GITLINKS = ROOT / "shared" / "dummy-org-gitlinks.json"
BENCHMARKS = ROOT / "benchmarks" / "matrix.json"
OUTPUT = ROOT / "conformance" / "ores-stack-cli" / "server-inventory.v1.json"


def is_server_repo(name: str) -> bool:
    return (
        name == "app"
        or name.endswith("-service")
        or name.endswith("-web")
        or name.endswith("-console")
    )


def role_for(name: str) -> str:
    if name == "app":
        return "application"
    if name.endswith("-web") or name.endswith("-console"):
        return "web"
    return "service"


def benchmark_modes() -> dict[str, dict[str, Any]]:
    data = json.loads(BENCHMARKS.read_text(encoding="utf-8"))
    if data.get("schema") != "ores.comparisons.smoke-matrix/v1":
        raise ValueError("unsupported benchmark matrix schema")
    return {
        item["scenario"]: item
        for item in data["projects"]
        if item.get("stack") == "ores-stack"
    }


def binary_target(scenario: str, repo: str, benchmarks: dict[str, dict[str, Any]]) -> str:
    item = benchmarks.get(scenario)
    if item is not None and repo == "app":
        for candidate in item.get("artifact_candidates", []):
            prefix = "target/release/"
            if isinstance(candidate, str) and candidate.startswith(prefix):
                return candidate[len(prefix):]
    if repo == "app":
        return f"{scenario}-ores-stack"
    return f"{scenario}-{repo}"


def render_inventory() -> dict[str, Any]:
    ledger = json.loads(GITLINKS.read_text(encoding="utf-8"))
    if ledger.get("schema") != "ores.comparisons.dummy-org-gitlinks/v1":
        raise ValueError("unsupported dummy-org gitlink schema")
    entries = [
        item
        for item in ledger.get("entries", [])
        if item.get("stack") == "ores-stack"
    ]
    benchmarks = benchmark_modes()

    infra_by_scenario = {
        item["scenario"]: item
        for item in entries
        if item.get("repo") == ".github"
    }
    servers: list[dict[str, Any]] = []
    for item in entries:
        repo = item.get("repo")
        scenario = item.get("scenario")
        if not isinstance(repo, str) or not isinstance(scenario, str) or not is_server_repo(repo):
            continue
        infra = infra_by_scenario.get(scenario)
        if infra is None:
            raise ValueError(f"{scenario}/{repo}: no .github infra owner")
        commit = item.get("commit")
        infra_commit = infra.get("commit")
        if not isinstance(commit, str) or len(commit) != 40:
            raise ValueError(f"{scenario}/{repo}: invalid source commit")
        if not isinstance(infra_commit, str) or len(infra_commit) != 40:
            raise ValueError(f"{scenario}/{repo}: invalid infra commit")

        benchmark = benchmarks.get(scenario)
        deployment_mode = (
            benchmark.get("deploy_mode")
            if benchmark is not None
            else "ores-compose-local"
        )
        servers.append(
            {
                "scenario": scenario,
                "role": role_for(repo),
                "repository": item["repository"],
                "sourceSha": commit,
                "binaryTarget": binary_target(scenario, repo, benchmarks),
                "infraOwner": {
                    "repository": infra["repository"],
                    "sourceSha": infra_commit,
                },
                "deploymentMode": deployment_mode,
            }
        )
    servers.sort(key=lambda value: (value["scenario"], value["repository"]))
    return {
        "schema": "ores.stack.server-inventory/v1",
        "source": "shared/dummy-org-gitlinks.json + benchmarks/matrix.json",
        "servers": servers,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()

    rendered = json.dumps(render_inventory(), indent=2, sort_keys=True) + "\n"
    if args.check:
        if not args.output.is_file() or args.output.read_text(encoding="utf-8") != rendered:
            print(f"ERROR {args.output}: server inventory is stale")
            return 1
        print(f"verified {args.output}")
        return 0

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
