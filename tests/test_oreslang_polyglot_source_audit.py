"""Negative tests for the dated Oreslang client/interface source audit."""
import copy
import json
import unittest

from scripts.verify_oreslang_polyglot_source_audit import validate


class OreslangPolyglotSourceAuditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open("shared/oreslang-polyglot-source-audit.json", encoding="utf-8") as f:
            cls.baseline = json.load(f)

    def test_all_18_distinct_orgs_36_repos_and_no_false_certification(self):
        self.assertEqual(validate(self.baseline), [])
        rows = self.baseline["organizations"]
        self.assertEqual(len(rows), 18)
        self.assertEqual(len({r["organization"] for r in rows}), 18)
        self.assertEqual(len({r["client_repository"] for r in rows}), 18)
        self.assertEqual(len({r["interface_repository"] for r in rows}), 18)

    def test_fewer_than_15_orgs_rejected(self):
        bad = copy.deepcopy(self.baseline)
        bad["organizations"] = bad["organizations"][:14]
        self.assertTrue(validate(bad))

    def test_fake_runtime_promotion_rejected(self):
        for field in ("client_oreslang_sources_observed", "interface_oreslang_sources_observed",
                      "execution_certified"):
            bad = copy.deepcopy(self.baseline)
            bad["organizations"][0][field] = True
            self.assertTrue(validate(bad), field)
        bad = copy.deepcopy(self.baseline)
        bad["certification"] = "passed"
        self.assertTrue(validate(bad))

    def test_identity_drift_and_cross_org_copy_rejected(self):
        for field, value in (
            ("organization", "evil/other"),
            ("client_repository", "elsewhere/other-clients"),
            ("interface_repository", "3FA-app/x/nested-interfaces"),
            ("branch", "latest"),
        ):
            bad = copy.deepcopy(self.baseline)
            bad["organizations"][0][field] = value
            self.assertTrue(validate(bad), field)
        bad = copy.deepcopy(self.baseline)
        bad["organizations"][1] = copy.deepcopy(bad["organizations"][0])
        self.assertTrue(validate(bad))

    def test_unknown_metadata_and_invalid_payload_rejected(self):
        for payload in (None, [], 0, {"organizations": []}):
            self.assertTrue(validate(payload))
        bad = copy.deepcopy(self.baseline)
        bad["organizations"][0]["runtime_certified"] = True
        self.assertTrue(validate(bad))
        bad = copy.deepcopy(self.baseline)
        bad["runtime_proven"] = True
        self.assertTrue(validate(bad))


if __name__ == "__main__":
    unittest.main()
