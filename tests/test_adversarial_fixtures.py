from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from scripts.verify_adversarial_fixtures import DEFAULT_FIXTURES, audit_case, verify_document


class AdversarialFleetFixturesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.document = json.loads(Path(DEFAULT_FIXTURES).read_text(encoding="utf-8"))

    def test_checked_in_fixtures_are_all_detected_exactly(self) -> None:
        self.assertEqual(verify_document(self.document), [])

    def test_auditor_does_not_mutate_input(self) -> None:
        case = copy.deepcopy(self.document["cases"][0])
        before = copy.deepcopy(case)
        audit_case(case)
        self.assertEqual(case, before)

    def test_zero_step_pass_is_rejected_but_real_pass_is_not(self) -> None:
        planted = {"kind": "evidence", "input": {"state": "passed", "executed_steps": 0}}
        clean = {"kind": "evidence", "input": {"state": "passed", "executed_steps": 7}}
        self.assertEqual(audit_case(planted), ["fleet.evidence.zero-step-pass"])
        self.assertEqual(audit_case(clean), [])


if __name__ == "__main__":
    unittest.main()
