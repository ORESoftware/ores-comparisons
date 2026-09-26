#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "conformance" / "ores-stack-cli" / "server-inventory.v1.json"
AUTHORITY = ROOT / "conformance" / "server-compatibility" / "receipt-authority.v1.json"
OUTPUT = ROOT / "conformance" / "server-compatibility" / "receipts" / "blocked"
SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA64 = re.compile(r"^[0-9a-f]{64}$")


def load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: root must be object")
    return value


def receipt_name(repository: str) -> str:
    return repository.replace("/", "--").replace(".", "_") + ".json"


def receipt_for(server: dict[str, Any], authority: dict[str, Any]) -> dict[str, Any]:
    zero = authority["blockedDigestSentinel"]
    role = "web" if server["role"] == "web" else "api"
    return {
        "schemaVersion": authority["receiptSchemaVersion"],
        "server": {
            "source": {
                "repository": server["repository"],
                "sha": server["sourceSha"],
            },
            "binary": server["binaryTarget"],
            "role": role,
        },
        "infra": {
            "source": {
                "repository": server["infraOwner"]["repository"],
                "sha": server["infraOwner"]["sourceSha"],
            },
            "configSha256": zero,
        },
        "toolchain": {
            "oresStack": authority["canonicalCli"],
            "oresCompose": authority["compose"],
        },
        "contractSha256": zero,
        "target": {
            "kind": "standalone",
            "adapter": authority["blockedAdapter"],
            "artifactSha256": zero,
        },
        "capabilities": [],
        "evidenceState": "blocked",
        "executedChecks": 1,
        "checks": [
            {"id": "source-identity-bound", "executed": True, "outcome": "passed"},
            {"id": "infra-config-digest", "executed": False, "outcome": "blocked"},
            {"id": "contract-digest-admission", "executed": False, "outcome": "blocked"},
            {"id": "adapter-artifact-certification", "executed": False, "outcome": "blocked"},
        ],
    }


def policy_errors(receipt: dict[str, Any], authority: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    zero = authority["blockedDigestSentinel"]

    for source in (
        receipt.get("server", {}).get("source", {}),
        receipt.get("infra", {}).get("source", {}),
        receipt.get("toolchain", {}).get("oresStack", {}),
        receipt.get("toolchain", {}).get("oresCompose", {}),
    ):
        sha = source.get("sha")
        if not isinstance(sha, str) or not SHA40.fullmatch(sha):
            errors.append("source identities require immutable 40-hex SHA")

    if receipt.get("toolchain", {}).get("oresStack") != authority.get("canonicalCli"):
        errors.append("oresStack identity drift")
    if receipt.get("toolchain", {}).get("oresCompose") != authority.get("compose"):
        errors.append("oresCompose identity drift")

    checks = receipt.get("checks", [])
    executed = [item for item in checks if isinstance(item, dict) and item.get("executed") is True]
    if receipt.get("executedChecks") != len(executed):
        errors.append("executedChecks mismatch")

    for check in checks:
        if not isinstance(check, dict):
            errors.append("check must be object")
            continue
        if check.get("executed") is False and check.get("outcome") in {"passed", "failed"}:
            errors.append("unexecuted check cannot be passed or failed")
        if check.get("executed") is True and check.get("outcome") == "not_run":
            errors.append("executed check cannot be not_run")

    digest_paths = (
        receipt.get("infra", {}).get("configSha256"),
        receipt.get("contractSha256"),
        receipt.get("target", {}).get("artifactSha256"),
    )
    for digest in digest_paths:
        if not isinstance(digest, str) or not SHA64.fullmatch(digest):
            errors.append("receipt digests must be lowercase SHA-256")

    state = receipt.get("evidenceState")
    if state == "passed":
        if any(digest == zero for digest in digest_paths):
            errors.append("passed receipt cannot contain blocked zero-digest sentinel")
        if not executed:
            errors.append("passed receipt must contain executed checks")
        if any(item.get("outcome") != "passed" for item in checks if isinstance(item, dict)):
            errors.append("passed receipt may contain only passed checks")
    elif state == "blocked":
        blocked = [item for item in checks if isinstance(item, dict) and item.get("outcome") == "blocked"]
        if not blocked:
            errors.append("blocked receipt must identify at least one blocked check")

    return sorted(set(errors))


def expected_receipts() -> dict[str, dict[str, Any]]:
    inventory = load(INVENTORY)
    authority = load(AUTHORITY)
    if inventory.get("schema") != "ores.stack.server-inventory/v1":
        raise ValueError("unexpected server inventory schema")
    if authority.get("schema") != "ores.comparisons.server-receipt-authority/v1":
        raise ValueError("unexpected receipt authority schema")
    if authority.get("receiptSchemaVersion") != "ores.api-docs.server-compatibility.v1":
        raise ValueError("receipt schema authority drift")
    zero = authority.get("blockedDigestSentinel")
    if not isinstance(zero, str) or zero != "0" * 64:
        raise ValueError("blocked digest sentinel drift")

    out: dict[str, dict[str, Any]] = {}
    for server in inventory.get("servers", []):
        receipt = receipt_for(server, authority)
        errors = policy_errors(receipt, authority)
        if errors:
            raise ValueError(f"{server.get('repository')}: {errors}")
        out[receipt_name(server["repository"])] = receipt
    if not out:
        raise ValueError("server inventory produced zero receipts")
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    expected = expected_receipts()
    OUTPUT.mkdir(parents=True, exist_ok=True)

    if args.check:
        actual_files = {path.name for path in OUTPUT.glob("*.json")}
        if actual_files != set(expected):
            print(f"ERROR receipt file set drift: expected={sorted(expected)} actual={sorted(actual_files)}")
            return 1
        failures: list[str] = []
        authority = load(AUTHORITY)
        for name, receipt in expected.items():
            path = OUTPUT / name
            actual = json.loads(path.read_text(encoding="utf-8"))
            if actual != receipt:
                failures.append(f"{name}: receipt drift")
            failures.extend(f"{name}: {item}" for item in policy_errors(actual, authority))
        if failures:
            for item in failures:
                print("ERROR", item)
            return 1
        print(f"verified {len(expected)} blocked source-bound server compatibility receipts")
        return 0

    for name, receipt in expected.items():
        (OUTPUT / name).write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {len(expected)} blocked source-bound server compatibility receipts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
