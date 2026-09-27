#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EXAMPLES = ROOT / "conformance" / "evidence" / "examples.v1.json"
STATES = {"passed", "failed", "blocked", "skipped", "not-run"}
PRODUCER_KINDS = {"source", "external", "test-org"}
SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
ROOT_KEYS = {"schema", "state", "identity", "producer", "executed_checks", "reason"}
IDENTITY_KEYS = {
    "repository",
    "commit",
    "dependencies_sha256",
    "config_sha256",
    "toolchain_sha256",
}
PRODUCER_KEYS = {"kind", "repository", "commit"}


def validate_identity(value: object, label: str = "identity") -> list[str]:
    errors: list[str] = []
    if not isinstance(value, dict):
        return [f"{label}: must be an object"]
    keys = set(value)
    if keys != IDENTITY_KEYS:
        errors.append(
            f"{label}: fields must be exactly {sorted(IDENTITY_KEYS)!r}, got {sorted(keys)!r}"
        )
    repository = value.get("repository")
    if not isinstance(repository, str) or not REPOSITORY.fullmatch(repository):
        errors.append(f"{label}.repository: invalid owner/repository identity")
    commit = value.get("commit")
    if not isinstance(commit, str) or not SHA40.fullmatch(commit):
        errors.append(f"{label}.commit: must be an exact lowercase 40-hex commit")
    for field in ("dependencies_sha256", "config_sha256", "toolchain_sha256"):
        digest = value.get(field)
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            errors.append(f"{label}.{field}: must be a lowercase SHA-256 digest")
    return errors


def validate_producer(value: object) -> list[str]:
    errors: list[str] = []
    if not isinstance(value, dict):
        return ["producer: must be an object"]
    keys = set(value)
    if keys != PRODUCER_KEYS:
        errors.append(
            f"producer: fields must be exactly {sorted(PRODUCER_KEYS)!r}, got {sorted(keys)!r}"
        )
    kind = value.get("kind")
    if kind not in PRODUCER_KINDS:
        errors.append(f"producer.kind: must be one of {sorted(PRODUCER_KINDS)!r}")
    repository = value.get("repository")
    if not isinstance(repository, str) or not REPOSITORY.fullmatch(repository):
        errors.append("producer.repository: invalid owner/repository identity")
    commit = value.get("commit")
    if not isinstance(commit, str) or not SHA40.fullmatch(commit):
        errors.append("producer.commit: must be an exact lowercase 40-hex commit")
    return errors


def validate_receipt(
    receipt: object,
    *,
    expected_identity: dict[str, Any] | None = None,
) -> list[str]:
    if not isinstance(receipt, dict):
        return ["receipt: must be a JSON object"]

    errors: list[str] = []
    unknown = set(receipt) - ROOT_KEYS
    missing = {"schema", "state", "identity", "producer", "executed_checks"} - set(receipt)
    if unknown:
        errors.append(f"receipt: unknown fields {sorted(unknown)!r}")
    if missing:
        errors.append(f"receipt: missing fields {sorted(missing)!r}")
    if receipt.get("schema") != "ores.comparisons.evidence-receipt/v1":
        errors.append("receipt.schema: unsupported schema")

    state = receipt.get("state")
    if state not in STATES:
        errors.append(f"receipt.state: must be one of {sorted(STATES)!r}")

    identity = receipt.get("identity")
    errors.extend(validate_identity(identity))
    producer = receipt.get("producer")
    errors.extend(validate_producer(producer))

    checks = receipt.get("executed_checks")
    if isinstance(checks, bool) or not isinstance(checks, int) or checks < 0:
        errors.append("receipt.executed_checks: must be a non-negative integer")
        checks = None

    reason = receipt.get("reason")
    if reason is not None and (not isinstance(reason, str) or not reason.strip()):
        errors.append("receipt.reason: must be a non-empty string when present")

    if state == "passed":
        if checks is not None and checks < 1:
            errors.append("receipt: passed evidence must execute at least one check")
    elif state == "failed":
        if checks is not None and checks < 1:
            errors.append("receipt: failed evidence must execute at least one check")
        if not isinstance(reason, str) or not reason.strip():
            errors.append("receipt: failed evidence requires a reason")
    elif state == "blocked":
        if not isinstance(reason, str) or not reason.strip():
            errors.append("receipt: blocked evidence requires a reason")
    elif state in {"skipped", "not-run"}:
        if checks is not None and checks != 0:
            errors.append(f"receipt: {state} evidence must have zero executed checks")
        if not isinstance(reason, str) or not reason.strip():
            errors.append(f"receipt: {state} evidence requires a reason")

    if isinstance(identity, dict) and isinstance(producer, dict):
        if producer.get("kind") == "source":
            if producer.get("repository") != identity.get("repository"):
                errors.append("producer: source evidence repository must equal tested repository")
            if producer.get("commit") != identity.get("commit"):
                errors.append("producer: source evidence commit must equal tested commit")

    if expected_identity is not None:
        identity_errors = validate_identity(expected_identity, "expected_identity")
        errors.extend(identity_errors)
        if not identity_errors and identity != expected_identity:
            errors.append(
                "receipt.identity: external/test-org evidence does not match expected source/dependency/config/toolchain identity"
            )

    return errors


def certifies(receipt: object, *, expected_identity: dict[str, Any] | None = None) -> bool:
    return (
        not validate_receipt(receipt, expected_identity=expected_identity)
        and isinstance(receipt, dict)
        and receipt.get("state") == "passed"
        and int(receipt.get("executed_checks", 0)) > 0
    )


def verify_examples(document: object) -> list[str]:
    if not isinstance(document, dict):
        return ["examples: document must be an object"]
    if document.get("schema") != "ores.comparisons.evidence-examples/v1":
        return ["examples: unsupported schema"]
    receipts = document.get("receipts")
    if not isinstance(receipts, list) or not receipts:
        return ["examples: receipts must be a non-empty list"]

    errors: list[str] = []
    seen: set[str] = set()
    for item in receipts:
        if not isinstance(item, dict):
            errors.append("examples: receipt entry must be an object")
            continue
        item_id = item.get("id")
        if not isinstance(item_id, str) or not item_id:
            errors.append("examples: receipt entry has no id")
            continue
        if item_id in seen:
            errors.append(f"{item_id}: duplicate example id")
        seen.add(item_id)
        receipt = item.get("receipt")
        for error in validate_receipt(receipt):
            errors.append(f"{item_id}: {error}")
        expected_certifies = item.get("certifies")
        if not isinstance(expected_certifies, bool):
            errors.append(f"{item_id}: certifies must be boolean")
        elif certifies(receipt) != expected_certifies:
            errors.append(
                f"{item_id}: expected certifies={expected_certifies}, got {certifies(receipt)}"
            )
    states = {
        item.get("receipt", {}).get("state")
        for item in receipts
        if isinstance(item, dict) and isinstance(item.get("receipt"), dict)
    }
    if states != STATES:
        errors.append(f"examples: must cover exactly all evidence states, got {sorted(states)!r}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--examples", type=Path, default=DEFAULT_EXAMPLES)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--expected-identity", type=Path)
    args = parser.parse_args()

    if args.receipt is None:
        document = json.loads(args.examples.read_text())
        errors = verify_examples(document)
    else:
        receipt = json.loads(args.receipt.read_text())
        expected = (
            json.loads(args.expected_identity.read_text())
            if args.expected_identity is not None
            else None
        )
        errors = validate_receipt(receipt, expected_identity=expected)

    if errors:
        print("evidence receipt verification FAILED")
        for error in errors:
            print(" -", error)
        return 1
    print("evidence receipt verification OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
