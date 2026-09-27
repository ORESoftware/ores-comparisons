from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from scripts.verify_fleet_proof_refinement import (
    DEFAULT_PROOF,
    audit_recovery_exercise,
    audit_refinement,
    exercise_recovery_drill,
)


class FleetProofRefinementTests(unittest.TestCase):
    def setUp(self) -> None:
        self.valid = json.loads(Path(DEFAULT_PROOF).read_text(encoding="utf-8"))
        self.assertEqual(audit_refinement(self.valid), [])

    def test_illegal_transition_and_trace_gap_are_detected(self) -> None:
        document = copy.deepcopy(self.valid)
        document["implementation_trace"][2].update(
            **{"from": "running", "to": "stopped", "event": "teleport"}
        )
        findings = audit_refinement(document)
        self.assertIn("fleet.refinement.illegal-transition", findings)
        self.assertIn("fleet.refinement.trace-discontinuity", findings)
        self.assertIn("fleet.refinement.transition-coverage-gap", findings)

    def test_missing_implementation_event_is_detected(self) -> None:
        document = copy.deepcopy(self.valid)
        document["implementation_trace"][1]["implementation_event"] = ""
        self.assertIn(
            "fleet.refinement.implementation-event-missing",
            audit_refinement(document),
        )

    def test_replayed_model_event_is_detected(self) -> None:
        document = copy.deepcopy(self.valid)
        document["implementation_trace"][2]["event"] = "start"
        findings = audit_refinement(document)
        self.assertIn("fleet.refinement.event-replayed", findings)
        self.assertIn("fleet.refinement.transition-coverage-gap", findings)

    def test_backup_restore_digest_mismatch_fails_closed(self) -> None:
        document = copy.deepcopy(self.valid)
        document["recovery"]["backup_sha256"] = "c" * 64
        document["recovery"]["restored_sha256"] = "d" * 64
        findings = audit_refinement(document)
        self.assertIn("fleet.recovery.backup-integrity-mismatch", findings)
        self.assertIn("fleet.recovery.restore-integrity-mismatch", findings)

    def test_mutable_backup_reference_and_source_are_rejected(self) -> None:
        document = copy.deepcopy(self.valid)
        document["recovery"].update(
            artifact_reference="s3://bucket/latest",
            source_commit="main",
            backup_immutable=False,
        )
        findings = audit_refinement(document)
        self.assertIn("fleet.recovery.mutable-artifact-reference", findings)
        self.assertIn("fleet.recovery.mutable-source-commit", findings)
        self.assertIn("fleet.recovery.backup-not-immutable", findings)

    def test_backup_source_drift_is_detected(self) -> None:
        document = copy.deepcopy(self.valid)
        document["recovery"]["backup_source_commit"] = "f" * 40
        self.assertIn(
            "fleet.recovery.backup-source-drift",
            audit_refinement(document),
        )

    def test_recovery_time_objective_is_measured_and_bounded(self) -> None:
        document = copy.deepcopy(self.valid)
        document["recovery"]["recovery_time_ms"] = 1001
        document["recovery"]["max_recovery_time_ms"] = 1000
        self.assertIn("fleet.recovery.rto-exceeded", audit_refinement(document))

    def test_executable_recovery_drill_restores_identical_bytes(self) -> None:
        exercise = exercise_recovery_drill(self.valid)
        expected = self.valid["recovery"]["artifact_sha256"]
        self.assertEqual(exercise["artifactSha256"], expected)
        self.assertEqual(exercise["backupSha256"], expected)
        self.assertEqual(exercise["restoredSha256"], expected)
        self.assertTrue(exercise["backupReadOnly"])
        self.assertEqual(audit_recovery_exercise(self.valid, exercise), [])

    def test_executable_recovery_drill_detects_declared_digest_tampering(self) -> None:
        exercise = exercise_recovery_drill(self.valid)
        tampered = copy.deepcopy(self.valid)
        tampered["recovery"]["artifact_sha256"] = "f" * 64
        findings = audit_recovery_exercise(tampered, exercise)
        self.assertIn("fleet.recovery.exercise-artifact-digest-mismatch", findings)
        self.assertIn("fleet.recovery.exercise-backup-digest-mismatch", findings)
        self.assertIn("fleet.recovery.exercise-restore-digest-mismatch", findings)

    def test_auditor_is_read_only(self) -> None:
        document = copy.deepcopy(self.valid)
        before = copy.deepcopy(document)
        audit_refinement(document)
        self.assertEqual(document, before)


if __name__ == "__main__":
    unittest.main()
