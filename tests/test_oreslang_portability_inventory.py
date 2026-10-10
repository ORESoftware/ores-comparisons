"""Regression tests for exact Oreslang cross-stack fixture identity admission."""

import copy
import json
import unittest

from scripts.verify_oreslang_portability import (
    EXPECTED_FIXTURE_REPOS,
    validate_fixture_repositories,
)


class OreslangPortabilityInventoryTests(unittest.TestCase):
    def setUp(self):
        with open("shared/oreslang-portability.json", encoding="utf-8") as f:
            self.inventory = json.load(f)["fixture_repositories"]

    def test_exact_real_source_fixture_inventory_is_accepted(self):
        self.assertEqual(validate_fixture_repositories(self.inventory), [])
        self.assertEqual({r["name"] for r in self.inventory}, EXPECTED_FIXTURE_REPOS)

    def test_missing_source_fails(self):
        self.assertTrue(validate_fixture_repositories(self.inventory[:1]))

    def test_duplicate_source_fails(self):
        self.assertTrue(validate_fixture_repositories([self.inventory[0], self.inventory[0]]))

    def test_auxiliary_infra_role_cannot_replace_app_source(self):
        bad = copy.deepcopy(self.inventory)
        bad[1]["name"] = "ores-dummy-org-oreslang-stack-infra"
        self.assertTrue(validate_fixture_repositories(bad))

    def test_auxiliary_lambdas_role_cannot_replace_app_source(self):
        bad = copy.deepcopy(self.inventory)
        bad[0]["name"] = "ores-dummy-org-oreslang-stack-lambdas"
        self.assertTrue(validate_fixture_repositories(bad))

    def test_unreviewed_branch_or_extra_metadata_fails(self):
        bad = copy.deepcopy(self.inventory)
        bad[0]["branch"] = "latest"
        self.assertTrue(validate_fixture_repositories(bad))
        bad = copy.deepcopy(self.inventory)
        bad[0]["sha"] = "0" * 40
        self.assertTrue(validate_fixture_repositories(bad))

    def test_invalid_identity_type_fails(self):
        self.assertTrue(validate_fixture_repositories([None, self.inventory[1]]))


if __name__ == "__main__":
    unittest.main()
