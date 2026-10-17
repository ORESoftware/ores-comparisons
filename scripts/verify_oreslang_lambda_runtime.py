#!/usr/bin/env python3
"""Fail-closed admission gate for the provider-neutral Oreslang Lambda runtime candidate."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHA_RE = re.compile(r"[0-9a-f]{40}\Z")

EXPECTED_TOP_LEVEL = {
    "schema",
    "language",
    "abi",
    "backend",
    "runtime_repository",
    "stack_cli_repository",
    "fixture_repository",
    "fixture_entry",
    "fixture_function",
    "event_encoding",
    "response_encoding",
    "max_payload_bytes",
    "runtime_policy",
    "scoped_permissions",
    "local_execution_status",
    "deployable",
    "provider",
    "implementation",
    "runtime_receipt",
}
EXPECTED_IMPLEMENTATION = {
    "runtime_pr",
    "runtime_head",
    "stack_cli_pr",
    "stack_cli_head",
    "fixture_pr",
    "fixture_head",
}


def validate_lambda_runtime(m: object, portability: object) -> list[str]:
    errors: list[str] = []

    def bad(message: str) -> None:
        errors.append(message)

    if not isinstance(m, dict) or set(m) != EXPECTED_TOP_LEVEL:
        return ["lambda runtime ledger must contain the exact v1 field set"]

    if m["schema"] != "ores.comparisons.oreslang-lambda-runtime/v1":
        bad("wrong Lambda runtime ledger schema")
    if m["language"] != "oreslang" or m["abi"] != "oreslang.lambda-runtime/v1":
        bad("wrong Oreslang Lambda language/ABI identity")
    if m["backend"] != "java-graal":
        bad("v1 Lambda candidate must stay on the Java/Graal execution backend")
    if m["runtime_repository"] != "ores-truffle-oreslang/oreslang-source.java":
        bad("wrong Oreslang runtime repository")
    if m["stack_cli_repository"] != "ores-truffle-oreslang/oreslang-stack-cli":
        bad("wrong Oreslang Stack CLI repository")
    if m["fixture_repository"] != "ores-dummy-org-oreslang-stack-lambdas":
        bad("wrong Oreslang Lambda fixture repository")
    if m["fixture_entry"] != "src/functions/hello/handler.ores":
        bad("wrong Oreslang Lambda fixture entry")
    if m["fixture_function"] != "handler.invoke":
        bad("wrong Oreslang Lambda public function")
    if m["event_encoding"] != "utf8-string" or m["response_encoding"] != "utf8-string":
        bad("v1 Lambda ABI must remain UTF-8 String -> String")
    if m["max_payload_bytes"] != 1024 * 1024:
        bad("v1 Lambda host payload bound changed")
    if m["runtime_policy"] != "strict-faas" or m["scoped_permissions"] != "deny-all":
        bad("Lambda candidate must remain strict-FaaS and deny-all")
    if m["local_execution_status"] != "candidate":
        bad("local Lambda execution cannot be promoted without an admitted runtime receipt")
    if m["deployable"] is not False or m["provider"] is not None:
        bad("local Lambda execution must not imply provider deployment")
    if m["runtime_receipt"] is not None:
        bad("candidate ledger cannot contain an unadmitted runtime receipt")

    implementation = m["implementation"]
    if not isinstance(implementation, dict) or set(implementation) != EXPECTED_IMPLEMENTATION:
        bad("Lambda implementation must bind exactly the three reviewed PR heads")
    else:
        expected_prs = {
            "runtime_pr": 1151,
            "stack_cli_pr": 17,
            "fixture_pr": 7,
        }
        for field, value in expected_prs.items():
            if implementation.get(field) != value:
                bad(f"{field}: unexpected implementation PR")
        for field in ("runtime_head", "stack_cli_head", "fixture_head"):
            value = implementation.get(field)
            if not isinstance(value, str) or not SHA_RE.fullmatch(value):
                bad(f"{field}: expected immutable 40-hex Git head")

    portability_repositories = []
    if isinstance(portability, dict):
        portability_repositories = portability.get("fixture_repositories", [])
    portability_names = {
        item.get("name")
        for item in portability_repositories
        if isinstance(item, dict)
    }
    if m["fixture_repository"] in portability_names:
        bad("auxiliary Lambda runtime fixture must not inflate the two-source portability corpus")
    if len(portability_names) != 2:
        bad("Lambda runtime gate requires the existing two-source portability corpus to remain intact")

    return errors


def verify() -> list[str]:
    ledger = json.loads((ROOT / "shared/oreslang-lambda-runtime.json").read_text())
    portability = json.loads((ROOT / "shared/oreslang-portability.json").read_text())
    return validate_lambda_runtime(ledger, portability)


if __name__ == "__main__":
    failures = verify()
    if failures:
        print("Oreslang Lambda runtime FAILED")
        for failure in failures:
            print(" -", failure)
        raise SystemExit(1)
    print("Oreslang Lambda runtime OK: provider-neutral candidate is pinned and deployment remains denied")
