"""Unit/mutation checks that green CI never overclaims private coverage."""

from __future__ import annotations

import copy
import unittest

from scripts.ci_evidence_receipt import make_receipt, status

SHA = "a" * 40
REPO = "ORESoftware/ores-comparisons"


def sample(**changes):
    kwargs = {
        "revision": SHA,
        "repository": REPO,
        "static_result": "success",
        "private_result": "success",
        "submodules_present": True,
        "private_credential": False,
        "source_credential": False,
    }
    kwargs.update(changes)
    return make_receipt(**kwargs)


class EvidenceReceiptTests(unittest.TestCase):
    def test_missing_credentials_never_pass_private_evidence(self):
        doc = sample()
        self.assertEqual(doc["scopes"]["private_contract_and_smoke"], "blocked")
        self.assertEqual(doc["scopes"]["native_source_remote_census"], "blocked")
        self.assertFalse(doc["full_runtime_proof"])

    def test_submodule_checkout_cannot_claim_public_project_content(self):
        self.assertEqual(sample()["scopes"]["public_project_content"], "blocked")

    def test_no_gitmodules_allows_public_content_gate(self):
        doc = sample(submodules_present=False)
        self.assertEqual(doc["scopes"]["public_project_content"], "passed")
        self.assertNotEqual(doc["coverage_status"], "complete")

    def test_credentials_allow_scope_only_after_successful_private_job(self):
        doc = sample(private_credential=True, source_credential=True)
        self.assertEqual(doc["scopes"]["private_contract_and_smoke"], "passed")
        self.assertEqual(doc["scopes"]["native_source_remote_census"], "passed")
        self.assertEqual(doc["scopes"]["runtime_proof_set_18"], "not_run")
        self.assertFalse(doc["full_runtime_proof"])

    def test_failed_private_job_never_passes_either_private_gate(self):
        doc = sample(private_credential=True, source_credential=True, private_result="failure")
        self.assertEqual(doc["scopes"]["private_contract_and_smoke"], "failed")
        self.assertEqual(doc["scopes"]["native_source_remote_census"], "failed")

    def test_cancelled_private_job_is_not_run(self):
        doc = sample(private_credential=True, source_credential=True, private_result="cancelled")
        self.assertEqual(doc["scopes"]["private_contract_and_smoke"], "not_run")

    def test_static_failure_cannot_claim_public_evidence(self):
        doc = sample(static_result="failure", submodules_present=False)
        self.assertEqual(doc["scopes"]["public_static_structure"], "failed")
        self.assertEqual(doc["scopes"]["public_project_content"], "failed")

    def test_unknown_outcome_or_non_boolean_credentials_refused(self):
        with self.assertRaises(ValueError):
            sample(static_result="pending")
        with self.assertRaises(ValueError):
            sample(private_credential="true")

    def test_invalid_identity_fails(self):
        with self.assertRaises(ValueError):
            sample(revision="moving-branch")
        with self.assertRaises(ValueError):
            sample(repository="../other")

    def test_receipt_has_only_presence_states_not_token_material(self):
        doc = sample(private_credential=True)
        self.assertEqual(doc["schema"], "ores.comparisons.ci-evidence-scope/v1")
        self.assertEqual(doc["revision"], SHA)
        self.assertNotIn("CROSS_REPO_READ_TOKEN", str(doc))
        self.assertEqual(doc["coverage_status"], "incomplete")

    def test_scope_states_exhaustive(self):
        self.assertEqual(
            {status(job, token) for job in ("success", "failure", "cancelled", "skipped") for token in (False, True)},
            {"passed", "blocked", "failed", "not_run"},
        )


if __name__ == "__main__":
    unittest.main()
