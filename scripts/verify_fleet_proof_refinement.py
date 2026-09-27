#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import tempfile
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PROOF = ROOT / "conformance" / "fleet-proof" / "refinement-recovery-valid.v1.json"

SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA64 = re.compile(r"^[0-9a-f]{64}$")
IMMUTABLE_ARTIFACT = re.compile(r"^sha256:[0-9a-f]{64}$")


def audit_refinement(document: dict[str, Any]) -> list[str]:
    findings: set[str] = set()

    if document.get("schema_version") != "ores.fleet-proof-refinement.v1":
        findings.add("fleet.refinement.unsupported-schema")

    model = document.get("model")
    if not isinstance(model, dict):
        return sorted(findings | {"fleet.refinement.model-missing"})

    initial = model.get("initial_state")
    terminal = model.get("terminal_state")
    transitions = model.get("allowed_transitions")
    if not isinstance(initial, str) or not initial:
        findings.add("fleet.refinement.initial-state-missing")
    if not isinstance(terminal, str) or not terminal:
        findings.add("fleet.refinement.terminal-state-missing")
    if not isinstance(transitions, list) or not transitions:
        findings.add("fleet.refinement.transitions-missing")
        transition_keys: set[tuple[str, str, str]] = set()
    else:
        transition_keys = set()
        for transition in transitions:
            if not isinstance(transition, dict):
                findings.add("fleet.refinement.transition-invalid")
                continue
            source = transition.get("from")
            target = transition.get("to")
            event = transition.get("event")
            if not all(isinstance(value, str) and value for value in (source, target, event)):
                findings.add("fleet.refinement.transition-invalid")
                continue
            key = (source, target, event)
            if key in transition_keys:
                findings.add("fleet.refinement.transition-duplicate")
            transition_keys.add(key)

    trace = document.get("implementation_trace")
    if not isinstance(trace, list) or not trace:
        findings.add("fleet.refinement.trace-missing")
    else:
        if isinstance(initial, str) and trace[0].get("from") != initial:
            findings.add("fleet.refinement.trace-wrong-initial-state")
        if isinstance(terminal, str) and trace[-1].get("to") != terminal:
            findings.add("fleet.refinement.trace-wrong-terminal-state")

        previous_to: str | None = None
        seen_events: set[str] = set()
        for index, step in enumerate(trace):
            if not isinstance(step, dict):
                findings.add("fleet.refinement.trace-step-invalid")
                continue
            source = step.get("from")
            target = step.get("to")
            event = step.get("event")
            implementation_event = step.get("implementation_event")
            if not all(isinstance(value, str) and value for value in (source, target, event)):
                findings.add("fleet.refinement.trace-step-invalid")
                continue
            if not isinstance(implementation_event, str) or not implementation_event:
                findings.add("fleet.refinement.implementation-event-missing")
            key = (source, target, event)
            if key not in transition_keys:
                findings.add("fleet.refinement.illegal-transition")
            if index > 0 and previous_to != source:
                findings.add("fleet.refinement.trace-discontinuity")
            previous_to = target
            if event in seen_events:
                findings.add("fleet.refinement.event-replayed")
            seen_events.add(event)

        model_events = {event for _, _, event in transition_keys}
        if model_events != seen_events:
            findings.add("fleet.refinement.transition-coverage-gap")

    recovery = document.get("recovery")
    if not isinstance(recovery, dict):
        findings.add("fleet.recovery.missing")
        return sorted(findings)

    payload = recovery.get("drill_payload")
    if not isinstance(payload, str) or not payload:
        findings.add("fleet.recovery.drill-payload-missing")
    else:
        payload_sha = hashlib.sha256(payload.encode("utf-8")).hexdigest()
        if recovery.get("artifact_sha256") != payload_sha:
            findings.add("fleet.recovery.declared-artifact-payload-mismatch")

    reference = recovery.get("artifact_reference")
    if not isinstance(reference, str) or IMMUTABLE_ARTIFACT.fullmatch(reference) is None:
        findings.add("fleet.recovery.mutable-artifact-reference")

    artifact_sha = recovery.get("artifact_sha256")
    backup_sha = recovery.get("backup_sha256")
    restored_sha = recovery.get("restored_sha256")
    for key, value in (
        ("artifact", artifact_sha),
        ("backup", backup_sha),
        ("restored", restored_sha),
    ):
        if not isinstance(value, str) or SHA64.fullmatch(value) is None:
            findings.add(f"fleet.recovery.invalid-{key}-digest")

    if isinstance(reference, str) and IMMUTABLE_ARTIFACT.fullmatch(reference):
        if isinstance(artifact_sha, str) and reference != f"sha256:{artifact_sha}":
            findings.add("fleet.recovery.reference-digest-mismatch")
    if (
        isinstance(artifact_sha, str)
        and isinstance(backup_sha, str)
        and artifact_sha != backup_sha
    ):
        findings.add("fleet.recovery.backup-integrity-mismatch")
    if (
        isinstance(artifact_sha, str)
        and isinstance(restored_sha, str)
        and artifact_sha != restored_sha
    ):
        findings.add("fleet.recovery.restore-integrity-mismatch")

    source_commit = recovery.get("source_commit")
    backup_source_commit = recovery.get("backup_source_commit")
    if not isinstance(source_commit, str) or SHA40.fullmatch(source_commit) is None:
        findings.add("fleet.recovery.mutable-source-commit")
    if backup_source_commit != source_commit:
        findings.add("fleet.recovery.backup-source-drift")
    if recovery.get("backup_immutable") is not True:
        findings.add("fleet.recovery.backup-not-immutable")

    recovery_time = recovery.get("recovery_time_ms")
    max_recovery_time = recovery.get("max_recovery_time_ms")
    if (
        not isinstance(recovery_time, int)
        or isinstance(recovery_time, bool)
        or recovery_time < 0
        or not isinstance(max_recovery_time, int)
        or isinstance(max_recovery_time, bool)
        or max_recovery_time <= 0
    ):
        findings.add("fleet.recovery.invalid-rto-measurement")
    elif recovery_time > max_recovery_time:
        findings.add("fleet.recovery.rto-exceeded")

    return sorted(findings)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def exercise_recovery_drill(document: dict[str, Any]) -> dict[str, Any]:
    recovery = document.get("recovery")
    if not isinstance(recovery, dict):
        raise RuntimeError("recovery configuration is missing")
    payload = recovery.get("drill_payload")
    if not isinstance(payload, str) or not payload:
        raise RuntimeError("recovery drill payload is missing")

    started_ns = time.perf_counter_ns()
    with tempfile.TemporaryDirectory(prefix="ores-fleet-recovery-") as raw:
        root = Path(raw)
        artifact = root / "artifact.bin"
        backup = root / "backup" / "artifact.bin"
        restored = root / "restored.bin"
        backup.parent.mkdir(parents=True)

        artifact.write_bytes(payload.encode("utf-8"))
        artifact_sha = _sha256_file(artifact)

        shutil.copyfile(artifact, backup)
        backup.chmod(0o444)
        backup_sha = _sha256_file(backup)
        backup_read_only = (backup.stat().st_mode & 0o222) == 0

        artifact.unlink()
        shutil.copyfile(backup, restored)
        restored_sha = _sha256_file(restored)

    recovery_time_ms = max(0, (time.perf_counter_ns() - started_ns) // 1_000_000)
    return {
        "artifactSha256": artifact_sha,
        "backupSha256": backup_sha,
        "restoredSha256": restored_sha,
        "backupReadOnly": backup_read_only,
        "recoveryTimeMs": recovery_time_ms,
    }


def audit_recovery_exercise(
    document: dict[str, Any],
    exercise: dict[str, Any],
) -> list[str]:
    findings: set[str] = set()
    recovery = document.get("recovery")
    if not isinstance(recovery, dict):
        return ["fleet.recovery.exercise-missing-config"]

    expected_artifact = recovery.get("artifact_sha256")
    if exercise.get("artifactSha256") != expected_artifact:
        findings.add("fleet.recovery.exercise-artifact-digest-mismatch")
    if exercise.get("backupSha256") != expected_artifact:
        findings.add("fleet.recovery.exercise-backup-digest-mismatch")
    if exercise.get("restoredSha256") != expected_artifact:
        findings.add("fleet.recovery.exercise-restore-digest-mismatch")
    if exercise.get("backupReadOnly") is not True:
        findings.add("fleet.recovery.exercise-backup-mutable")

    measured = exercise.get("recoveryTimeMs")
    maximum = recovery.get("max_recovery_time_ms")
    if (
        not isinstance(measured, int)
        or isinstance(measured, bool)
        or measured < 0
        or not isinstance(maximum, int)
        or isinstance(maximum, bool)
        or maximum <= 0
    ):
        findings.add("fleet.recovery.exercise-rto-invalid")
    elif measured > maximum:
        findings.add("fleet.recovery.exercise-rto-exceeded")

    return sorted(findings)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify model-to-implementation refinement and immutable recovery evidence."
    )
    parser.add_argument("--proof", type=Path, default=DEFAULT_PROOF)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument(
        "--exercise",
        action="store_true",
        help="materialize, back up, restore, hash, and time the synthetic recovery artifact",
    )
    args = parser.parse_args()

    document = json.loads(args.proof.read_text(encoding="utf-8"))
    findings = audit_refinement(document)
    recovery_exercise: dict[str, Any] | None = None
    if args.exercise:
        try:
            recovery_exercise = exercise_recovery_drill(document)
            findings = sorted(
                set(findings) | set(audit_recovery_exercise(document, recovery_exercise))
            )
        except Exception as error:
            findings = sorted(set(findings) | {"fleet.recovery.exercise-failed"})
            recovery_exercise = {"error": str(error)}

    receipt = {
        "schema": "ores.fleet-refinement-recovery-proof/v1",
        "state": "passed" if not findings else "failed",
        "executed_steps": 3 if args.exercise else 2,
        "proof": str(args.proof),
        "findings": findings,
    }
    if recovery_exercise is not None:
        receipt["recoveryExercise"] = recovery_exercise
    encoded = json.dumps(receipt, indent=2, sort_keys=True) + "\n"
    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    return 0 if not findings else 1


if __name__ == "__main__":
    raise SystemExit(main())
