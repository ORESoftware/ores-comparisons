#!/usr/bin/env python3
"""Verify that every synthetic fleet fixture produces its deterministic planted findings."""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURES = ROOT / "conformance" / "adversarial" / "fleet-fixtures.json"


def audit_case(case: dict[str, Any]) -> list[str]:
    kind = case.get("kind")
    data = case.get("input", {})
    findings: list[str] = []

    if kind == "cli-pin":
        if data.get("repository") == "ORESoftware/ores-stack":
            findings.append("fleet.cli.legacy-repository")
        version = data.get("version")
        if not isinstance(version, str) or len(version) != 40 or any(ch not in "0123456789abcdefABCDEF" for ch in version):
            findings.append("fleet.cli.mutable-version")
    elif kind == "cli-path":
        if data.get("resolved") != data.get("pinned"):
            findings.append("fleet.cli.path-shadowed")
    elif kind == "server-contract":
        if data.get("handler_digest") != data.get("deployment_digest"):
            findings.append("fleet.server.contract-digest-mismatch")
    elif kind == "compose-discovery":
        candidates = data.get("candidates", [])
        if len(candidates) > 1 and not data.get("explicit_selection"):
            findings.append("fleet.compose.ambiguous-discovery")
    elif kind == "provider-capabilities":
        required = set(data.get("required", []))
        supported = set(data.get("supported", []))
        if not required.issubset(supported):
            findings.append("fleet.deploy.unsupported-capability")
    elif kind == "artifact":
        reference = data.get("reference", "")
        digest = data.get("digest")
        if not digest or (isinstance(reference, str) and reference.endswith(":latest")):
            findings.append("fleet.deploy.mutable-artifact")
    elif kind == "evidence":
        if data.get("state") == "passed" and int(data.get("executed_steps", 0)) == 0:
            findings.append("fleet.evidence.zero-step-pass")
    elif kind == "receipt":
        if data.get("expected_source_digest") != data.get("observed_source_digest"):
            findings.append("fleet.evidence.source-digest-mismatch")
    else:
        findings.append("fleet.fixture.unknown-kind")

    return sorted(findings)


def verify_document(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if document.get("schema_version") != "ores.fleet-adversarial.v1":
        errors.append("document: unsupported schema_version")

    cases = document.get("cases")
    if not isinstance(cases, list) or not cases:
        return errors + ["document: cases must be a non-empty list"]

    seen: set[str] = set()
    for case in cases:
        case_id = case.get("id")
        if not isinstance(case_id, str) or not case_id:
            errors.append("case: missing id")
            continue
        if case_id in seen:
            errors.append(f"{case_id}: duplicate id")
        seen.add(case_id)

        before = copy.deepcopy(case)
        actual = audit_case(case)
        expected = sorted(case.get("expected_findings", []))
        if actual != expected:
            errors.append(f"{case_id}: expected {expected!r}, got {actual!r}")
        if case != before:
            errors.append(f"{case_id}: auditor mutated fixture input")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_FIXTURES)
    args = parser.parse_args()

    document = json.loads(args.fixtures.read_text(encoding="utf-8"))
    errors = verify_document(document)
    if errors:
        for error in errors:
            print(f"ERROR {error}")
        return 1

    cases = document["cases"]
    print(f"verified {len(cases)} adversarial fleet fixtures")
    for case in cases:
        print(f"{case['id']}: {','.join(audit_case(case))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
