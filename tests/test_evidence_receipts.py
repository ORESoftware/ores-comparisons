from __future__ import annotations

import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_evidence_receipts",
    ROOT / "scripts" / "verify_evidence_receipts.py",
)
assert SPEC is not None and SPEC.loader is not None
evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evidence)


def identity(label: str = "a") -> dict[str, str]:
    return {
        "repository": "ORESoftware/example",
        "commit": label * 40,
        "dependencies_sha256": "b" * 64,
        "config_sha256": "c" * 64,
        "toolchain_sha256": "d" * 64,
    }


def passed_receipt() -> dict[str, object]:
    return {
        "schema": "ores.comparisons.evidence-receipt/v1",
        "state": "passed",
        "identity": identity(),
        "producer": {
            "kind": "source",
            "repository": "ORESoftware/example",
            "commit": "a" * 40,
        },
        "executed_checks": 4,
    }


class EvidenceReceiptTests(unittest.TestCase):
    def test_checked_in_examples_cover_all_states_and_semantics(self) -> None:
        document = json.loads(
            (ROOT / "conformance" / "evidence" / "examples.v1.json").read_text()
        )
        self.assertEqual(evidence.verify_examples(document), [])

    def test_passed_zero_check_receipt_never_certifies(self) -> None:
        receipt = passed_receipt()
        receipt["executed_checks"] = 0
        errors = evidence.validate_receipt(receipt)
        self.assertTrue(any("at least one check" in error for error in errors))
        self.assertFalse(evidence.certifies(receipt))

    def test_nonpassing_states_never_certify(self) -> None:
        for state in ("failed", "blocked", "skipped", "not-run"):
            receipt = passed_receipt()
            receipt["state"] = state
            receipt["reason"] = f"{state} reason"
            receipt["executed_checks"] = 1 if state in {"failed", "blocked"} else 0
            self.assertEqual(evidence.validate_receipt(receipt), [])
            self.assertFalse(evidence.certifies(receipt))

    def test_external_evidence_must_match_exact_expected_identity(self) -> None:
        receipt = passed_receipt()
        receipt["producer"] = {
            "kind": "test-org",
            "repository": "ores-test-org/evidence-carrier",
            "commit": "e" * 40,
        }
        expected = identity()
        self.assertTrue(evidence.certifies(receipt, expected_identity=expected))

        drifted = copy.deepcopy(expected)
        drifted["config_sha256"] = "f" * 64
        errors = evidence.validate_receipt(receipt, expected_identity=drifted)
        self.assertTrue(any("does not match expected" in error for error in errors))
        self.assertFalse(evidence.certifies(receipt, expected_identity=drifted))

    def test_source_producer_must_equal_tested_repo_and_commit(self) -> None:
        receipt = passed_receipt()
        receipt["producer"]["repository"] = "ORESoftware/other"
        receipt["producer"]["commit"] = "e" * 40
        errors = evidence.validate_receipt(receipt)
        self.assertTrue(any("source evidence repository" in error for error in errors))
        self.assertTrue(any("source evidence commit" in error for error in errors))

    def test_skipped_and_not_run_cannot_claim_executed_checks(self) -> None:
        for state in ("skipped", "not-run"):
            receipt = passed_receipt()
            receipt["state"] = state
            receipt["reason"] = "not executed"
            receipt["executed_checks"] = 2
            errors = evidence.validate_receipt(receipt)
            self.assertTrue(any("zero executed checks" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
