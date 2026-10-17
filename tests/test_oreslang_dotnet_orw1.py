"""Mutation tests for the canonical Oreslang ORW1 wire authority."""

import copy
import json
import unittest

from scripts.verify_oreslang_dotnet_orw1 import validate


class OreslangOrw1Tests(unittest.TestCase):
    def setUp(self):
        with open("shared/oreslang-dotnet-orw1.json", encoding="utf-8") as f:
            self.contract = json.load(f)

    def test_current_contract_is_accepted(self):
        self.assertEqual(validate(self.contract), [])

    def test_kind_number_cannot_drift(self):
        bad = copy.deepcopy(self.contract)
        bad["frame_kinds"]["response"] = 9
        self.assertTrue(validate(bad))

    def test_payload_limit_cannot_widen(self):
        bad = copy.deepcopy(self.contract)
        bad["constraints"]["max_payload_bytes"] = 2 * 1024 * 1024
        self.assertTrue(validate(bad))

    def test_deadline_limit_cannot_widen(self):
        bad = copy.deepcopy(self.contract)
        bad["constraints"]["max_invocation_ms"] = 60000
        self.assertTrue(validate(bad))

    def test_vector_bytes_are_recomputed(self):
        bad = copy.deepcopy(self.contract)
        bad["vectors"][1]["hex"] = "00" * 28
        self.assertTrue(validate(bad))

    def test_dotnet_head_is_exact(self):
        bad = copy.deepcopy(self.contract)
        bad["implementations"]["dotnet"]["head"] = "0" * 40
        self.assertTrue(validate(bad))

    def test_scintilla_head_is_exact(self):
        bad = copy.deepcopy(self.contract)
        bad["implementations"]["scintilla"]["head"] = "0" * 40
        self.assertTrue(validate(bad))

    def test_scintilla_cannot_claim_execution(self):
        bad = copy.deepcopy(self.contract)
        bad["implementations"]["scintilla"]["execution_status"] = "verified"
        self.assertTrue(validate(bad))

    def test_live_runtime_cannot_be_registered(self):
        bad = copy.deepcopy(self.contract)
        bad["live_runtime_registered"] = True
        self.assertTrue(validate(bad))

    def test_runtime_receipt_cannot_be_invented(self):
        bad = copy.deepcopy(self.contract)
        bad["runtime_receipt"] = {"status": "passed"}
        self.assertTrue(validate(bad))

    def test_status_cannot_be_promoted(self):
        bad = copy.deepcopy(self.contract)
        bad["status"] = "verified"
        self.assertTrue(validate(bad))


if __name__ == "__main__":
    unittest.main()
