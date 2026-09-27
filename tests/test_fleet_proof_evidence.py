from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from scripts.verify_fleet_proof_evidence import DEFAULT_EVIDENCE, audit_evidence


class FleetProofEvidenceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.valid = json.loads(Path(DEFAULT_EVIDENCE).read_text(encoding="utf-8"))
        self.assertEqual(audit_evidence(self.valid), [])

    def findings(self, mutate) -> list[str]:
        document = copy.deepcopy(self.valid)
        mutate(document)
        return audit_evidence(document)

    def test_zero_step_pass_cannot_certify(self) -> None:
        findings = self.findings(
            lambda document: document["evidence"].update(executed_steps=0)
        )
        self.assertIn("fleet.evidence.zero-step-pass", findings)

    def test_states_are_distinct_and_require_state_specific_reasoning(self) -> None:
        failed = copy.deepcopy(self.valid)
        failed["evidence"].update(
            state="failed",
            executed_steps=3,
            failure_code="server.contract.mismatch",
        )
        self.assertEqual(audit_evidence(failed), [])

        blocked = copy.deepcopy(self.valid)
        blocked["evidence"].update(
            state="blocked",
            executed_steps=0,
            blocker_code="credential.unavailable",
        )
        self.assertEqual(audit_evidence(blocked), [])

        skipped = copy.deepcopy(self.valid)
        skipped["evidence"].update(
            state="skipped",
            executed_steps=0,
            skip_reason="upstream wave failed",
        )
        self.assertEqual(audit_evidence(skipped), [])

        not_run = copy.deepcopy(self.valid)
        not_run["evidence"].update(
            state="not-run",
            executed_steps=0,
            not_run_reason="not scheduled",
        )
        self.assertEqual(audit_evidence(not_run), [])

        broken_failed = copy.deepcopy(failed)
        broken_failed["evidence"].pop("failure_code")
        self.assertIn("fleet.evidence.failed-without-code", audit_evidence(broken_failed))

        broken_blocked = copy.deepcopy(blocked)
        broken_blocked["evidence"].pop("blocker_code")
        self.assertIn("fleet.evidence.blocked-without-code", audit_evidence(broken_blocked))

        broken_skipped = copy.deepcopy(skipped)
        broken_skipped["evidence"].pop("skip_reason")
        self.assertIn("fleet.evidence.skipped-without-reason", audit_evidence(broken_skipped))

        broken_not_run = copy.deepcopy(not_run)
        broken_not_run["evidence"]["executed_steps"] = 1
        self.assertIn("fleet.evidence.not-run-executed", audit_evidence(broken_not_run))

    def test_all_source_identity_dimensions_are_immutable(self) -> None:
        mutations = {
            "repository": (
                "main",
                "fleet.evidence.mutable-repository-identity",
            ),
            "commit": (
                "main",
                "fleet.evidence.mutable-source-commit",
            ),
            "dependency_digest": (
                "sha256:short",
                "fleet.evidence.invalid-dependency-digest",
            ),
            "config_digest": (
                "latest",
                "fleet.evidence.invalid-config-digest",
            ),
            "toolchain_digest": (
                "sha256:xyz",
                "fleet.evidence.invalid-toolchain-digest",
            ),
        }
        for field, (value, finding) in mutations.items():
            with self.subTest(field=field):
                findings = self.findings(
                    lambda document, field=field, value=value: document["evidence"].__setitem__(
                        field, value
                    )
                )
                self.assertIn(finding, findings)

    def test_external_test_org_evidence_must_bind_exact_source(self) -> None:
        findings = self.findings(
            lambda document: document["evidence"]["external_binding"].update(
                source_commit="f" * 40,
                observed_source_digest="sha256:" + "1" * 64,
                observed_dependency_digest="sha256:" + "2" * 64,
            )
        )
        self.assertIn("fleet.evidence.external-commit-drift", findings)
        self.assertIn("fleet.evidence.external-source-digest-drift", findings)
        self.assertIn("fleet.evidence.external-dependency-digest-drift", findings)

    def test_runtime_policy_contradiction_is_detected_per_dimension(self) -> None:
        findings = self.findings(
            lambda document: document["policy_layers"]["middleware"].update(
                auth_required=False,
                telemetry_redaction="optional",
            )
        )
        self.assertIn("fleet.policy.auth-required-contradiction", findings)
        self.assertIn("fleet.policy.telemetry-redaction-contradiction", findings)

    def test_trace_requires_full_stage_chain_and_stable_tenant_correlation(self) -> None:
        document = copy.deepcopy(self.valid)
        document["trace"]["events"].pop(2)
        document["trace"]["events"][1]["correlation_id"] = "different-correlation"
        document["trace"]["events"][-1]["tenant_id"] = "other-tenant"
        findings = audit_evidence(document)
        self.assertIn("fleet.telemetry.trace-stage-gap", findings)
        self.assertIn("fleet.telemetry.correlation-drift", findings)
        self.assertIn("fleet.telemetry.tenant-drift", findings)

    def test_telemetry_cardinality_and_payload_are_bounded(self) -> None:
        document = copy.deepcopy(self.valid)
        document["trace"]["max_attribute_keys"] = 1
        document["trace"]["max_payload_bytes"] = 128
        document["trace"]["events"][0]["attributes"] = {
            "a": "x" * 200,
            "b": "y" * 200,
        }
        findings = audit_evidence(document)
        self.assertIn("fleet.telemetry.cardinality-budget-exceeded", findings)
        self.assertIn("fleet.telemetry.payload-budget-exceeded", findings)

    def test_canary_secret_is_rejected_on_every_output_surface(self) -> None:
        for surface in ("logs", "traces", "build_output", "receipts"):
            with self.subTest(surface=surface):
                document = copy.deepcopy(self.valid)
                document["outputs"][surface].append(
                    f"leaked={document['canary_secret']}"
                )
                findings = audit_evidence(document)
                self.assertIn(
                    f"fleet.telemetry.canary-leak-{surface.replace('_', '-')}",
                    findings,
                )

    def test_auditor_does_not_mutate_document(self) -> None:
        document = copy.deepcopy(self.valid)
        before = copy.deepcopy(document)
        audit_evidence(document)
        self.assertEqual(document, before)


if __name__ == "__main__":
    unittest.main()
