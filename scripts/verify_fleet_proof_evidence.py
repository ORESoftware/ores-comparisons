#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EVIDENCE = ROOT / "conformance" / "fleet-proof" / "evidence-valid.v1.json"

SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")
GITHUB_REPOSITORY = re.compile(r"^https://github\.com/[^/\s]+/[^/\s]+$")
ALLOWED_STATES = {"passed", "failed", "blocked", "skipped", "not-run"}
POLICY_LAYERS = ("stack", "compose", "auth", "middleware", "rate_limit", "telemetry")
POLICY_KEYS = (
    "tenant_isolation",
    "auth_required",
    "rate_limit_enabled",
    "telemetry_redaction",
    "deployment_mode",
)
TRACE_STAGES = ("ingress", "server", "queue", "database")
OUTPUT_SURFACES = ("logs", "traces", "build_output", "receipts")


def _is_nonempty_string(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _scan_for_secret(value: object, secret: str) -> bool:
    if isinstance(value, str):
        return secret in value
    if isinstance(value, dict):
        return any(_scan_for_secret(item, secret) for item in value.values())
    if isinstance(value, list):
        return any(_scan_for_secret(item, secret) for item in value)
    return False


def audit_evidence(document: dict[str, Any]) -> list[str]:
    findings: set[str] = set()

    if document.get("schema_version") != "ores.fleet-proof-evidence.v1":
        findings.add("fleet.proof.unsupported-schema")

    evidence = document.get("evidence")
    if not isinstance(evidence, dict):
        return sorted(findings | {"fleet.evidence.missing"})

    state = evidence.get("state")
    if state not in ALLOWED_STATES:
        findings.add("fleet.evidence.invalid-state")

    executed_steps = evidence.get("executed_steps")
    if not isinstance(executed_steps, int) or isinstance(executed_steps, bool) or executed_steps < 0:
        findings.add("fleet.evidence.invalid-step-count")
        executed_steps_value = -1
    else:
        executed_steps_value = executed_steps

    if state == "passed" and executed_steps_value <= 0:
        findings.add("fleet.evidence.zero-step-pass")
    elif state == "failed":
        if executed_steps_value <= 0:
            findings.add("fleet.evidence.failed-without-execution")
        if not _is_nonempty_string(evidence.get("failure_code")):
            findings.add("fleet.evidence.failed-without-code")
    elif state == "blocked":
        if not _is_nonempty_string(evidence.get("blocker_code")):
            findings.add("fleet.evidence.blocked-without-code")
    elif state == "skipped":
        if not _is_nonempty_string(evidence.get("skip_reason")):
            findings.add("fleet.evidence.skipped-without-reason")
    elif state == "not-run":
        if executed_steps_value != 0:
            findings.add("fleet.evidence.not-run-executed")
        if not _is_nonempty_string(evidence.get("not_run_reason")):
            findings.add("fleet.evidence.not-run-without-reason")

    repository = evidence.get("repository")
    if not isinstance(repository, str) or GITHUB_REPOSITORY.fullmatch(repository) is None:
        findings.add("fleet.evidence.mutable-repository-identity")
    commit = evidence.get("commit")
    if not isinstance(commit, str) or SHA40.fullmatch(commit) is None:
        findings.add("fleet.evidence.mutable-source-commit")
    for field in ("dependency_digest", "config_digest", "toolchain_digest"):
        value = evidence.get(field)
        if not isinstance(value, str) or SHA256.fullmatch(value) is None:
            findings.add(f"fleet.evidence.invalid-{field.replace('_', '-')}")

    scope = evidence.get("evidence_scope")
    if scope not in {"source", "external", "test-org"}:
        findings.add("fleet.evidence.invalid-scope")
    if scope in {"external", "test-org"}:
        binding = evidence.get("external_binding")
        if not isinstance(binding, dict):
            findings.add("fleet.evidence.external-binding-missing")
        else:
            source_repository = binding.get("source_repository")
            source_commit = binding.get("source_commit")
            if source_repository != repository:
                findings.add("fleet.evidence.external-repository-drift")
            if source_commit != commit:
                findings.add("fleet.evidence.external-commit-drift")
            for expected_key, observed_key, finding in (
                (
                    "expected_source_digest",
                    "observed_source_digest",
                    "fleet.evidence.external-source-digest-drift",
                ),
                (
                    "expected_dependency_digest",
                    "observed_dependency_digest",
                    "fleet.evidence.external-dependency-digest-drift",
                ),
            ):
                expected = binding.get(expected_key)
                observed = binding.get(observed_key)
                if (
                    not isinstance(expected, str)
                    or SHA256.fullmatch(expected) is None
                    or not isinstance(observed, str)
                    or SHA256.fullmatch(observed) is None
                    or expected != observed
                ):
                    findings.add(finding)

    policy_layers = document.get("policy_layers")
    if not isinstance(policy_layers, dict):
        findings.add("fleet.policy.layers-missing")
    else:
        missing_layers = [layer for layer in POLICY_LAYERS if not isinstance(policy_layers.get(layer), dict)]
        if missing_layers:
            findings.add("fleet.policy.layers-missing")
        else:
            for key in POLICY_KEYS:
                values = [policy_layers[layer].get(key) for layer in POLICY_LAYERS]
                if any(value is None for value in values):
                    findings.add(f"fleet.policy.{key.replace('_', '-')}-missing")
                elif any(value != values[0] for value in values[1:]):
                    findings.add(f"fleet.policy.{key.replace('_', '-')}-contradiction")

    trace = document.get("trace")
    if not isinstance(trace, dict):
        findings.add("fleet.telemetry.trace-missing")
    else:
        correlation_id = trace.get("correlation_id")
        tenant_id = trace.get("tenant_id")
        if not _is_nonempty_string(correlation_id):
            findings.add("fleet.telemetry.correlation-missing")
        if not _is_nonempty_string(tenant_id):
            findings.add("fleet.telemetry.tenant-missing")

        max_attribute_keys = trace.get("max_attribute_keys")
        max_payload_bytes = trace.get("max_payload_bytes")
        if (
            not isinstance(max_attribute_keys, int)
            or isinstance(max_attribute_keys, bool)
            or not 1 <= max_attribute_keys <= 64
        ):
            findings.add("fleet.telemetry.invalid-cardinality-budget")
            max_attribute_keys_value = 0
        else:
            max_attribute_keys_value = max_attribute_keys
        if (
            not isinstance(max_payload_bytes, int)
            or isinstance(max_payload_bytes, bool)
            or not 128 <= max_payload_bytes <= 65536
        ):
            findings.add("fleet.telemetry.invalid-payload-budget")
            max_payload_bytes_value = 0
        else:
            max_payload_bytes_value = max_payload_bytes

        events = trace.get("events")
        if not isinstance(events, list):
            findings.add("fleet.telemetry.trace-events-missing")
        else:
            stages = [
                event.get("stage")
                for event in events
                if isinstance(event, dict)
            ]
            if tuple(stages) != TRACE_STAGES:
                findings.add("fleet.telemetry.trace-stage-gap")
            for event in events:
                if not isinstance(event, dict):
                    findings.add("fleet.telemetry.trace-event-invalid")
                    continue
                if event.get("correlation_id") != correlation_id:
                    findings.add("fleet.telemetry.correlation-drift")
                if event.get("tenant_id") != tenant_id:
                    findings.add("fleet.telemetry.tenant-drift")
                attributes = event.get("attributes")
                if not isinstance(attributes, dict):
                    findings.add("fleet.telemetry.attributes-invalid")
                elif max_attribute_keys_value and len(attributes) > max_attribute_keys_value:
                    findings.add("fleet.telemetry.cardinality-budget-exceeded")
                if max_payload_bytes_value:
                    encoded = json.dumps(
                        event,
                        sort_keys=True,
                        separators=(",", ":"),
                    ).encode("utf-8")
                    if len(encoded) > max_payload_bytes_value:
                        findings.add("fleet.telemetry.payload-budget-exceeded")

    canary = document.get("canary_secret")
    if not _is_nonempty_string(canary):
        findings.add("fleet.telemetry.canary-missing")
    else:
        outputs = document.get("outputs")
        if not isinstance(outputs, dict):
            findings.add("fleet.telemetry.outputs-missing")
        else:
            for surface in OUTPUT_SURFACES:
                if surface not in outputs:
                    findings.add(f"fleet.telemetry.{surface.replace('_', '-')}-missing")
                elif _scan_for_secret(outputs[surface], canary):
                    findings.add(f"fleet.telemetry.canary-leak-{surface.replace('_', '-')}")

    return sorted(findings)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify source-bound fleet evidence, policy consistency, trace continuity, and redaction."
    )
    parser.add_argument("--evidence", type=Path, default=DEFAULT_EVIDENCE)
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()

    document = json.loads(args.evidence.read_text(encoding="utf-8"))
    findings = audit_evidence(document)
    receipt = {
        "schema": "ores.fleet-proof-gate/v1",
        "state": "passed" if not findings else "failed",
        "executed_steps": 1,
        "evidence": str(args.evidence),
        "findings": findings,
    }
    encoded = json.dumps(receipt, indent=2, sort_keys=True) + "\n"
    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    return 0 if not findings else 1


if __name__ == "__main__":
    raise SystemExit(main())
