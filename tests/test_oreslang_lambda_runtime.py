"""Mutation coverage for the provider-neutral Oreslang Lambda runtime candidate."""

import copy
import json
import unittest

from scripts.verify_oreslang_lambda_runtime import validate_lambda_runtime


class OreslangLambdaRuntimeTests(unittest.TestCase):
    def setUp(self):
        with open("shared/oreslang-lambda-runtime.json", encoding="utf-8") as f:
            self.runtime = json.load(f)
        with open("shared/oreslang-portability.json", encoding="utf-8") as f:
            self.portability = json.load(f)

    def validate(self, runtime=None, portability=None):
        return validate_lambda_runtime(
            self.runtime if runtime is None else runtime,
            self.portability if portability is None else portability,
        )

    def test_current_candidate_is_accepted(self):
        self.assertEqual(self.validate(), [])

    def test_cannot_claim_verified_execution_without_receipt(self):
        bad = copy.deepcopy(self.runtime)
        bad["local_execution_status"] = "verified"
        self.assertTrue(self.validate(bad))

    def test_cannot_claim_provider_or_deployment(self):
        bad = copy.deepcopy(self.runtime)
        bad["deployable"] = True
        bad["provider"] = "aws-lambda"
        self.assertTrue(self.validate(bad))

    def test_policy_cannot_widen(self):
        bad = copy.deepcopy(self.runtime)
        bad["scoped_permissions"] = "allow-all"
        self.assertTrue(self.validate(bad))

    def test_abi_or_payload_drift_fails(self):
        bad = copy.deepcopy(self.runtime)
        bad["abi"] = "aws.lambda/v1"
        bad["max_payload_bytes"] = 2 * 1024 * 1024
        self.assertTrue(self.validate(bad))

    def test_unpinned_or_wrong_pr_head_fails(self):
        bad = copy.deepcopy(self.runtime)
        bad["implementation"]["runtime_head"] = "main"
        self.assertTrue(self.validate(bad))
        bad = copy.deepcopy(self.runtime)
        bad["implementation"]["stack_cli_pr"] = 18
        self.assertTrue(self.validate(bad))

    def test_lambda_fixture_cannot_replace_portability_source(self):
        bad_portability = copy.deepcopy(self.portability)
        bad_portability["fixture_repositories"][0]["name"] = self.runtime["fixture_repository"]
        self.assertTrue(self.validate(portability=bad_portability))

    def test_extra_metadata_fails_closed(self):
        bad = copy.deepcopy(self.runtime)
        bad["provider_hint"] = "aws"
        self.assertTrue(self.validate(bad))


if __name__ == "__main__":
    unittest.main()
