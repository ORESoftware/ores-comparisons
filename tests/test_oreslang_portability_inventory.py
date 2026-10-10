"""Regression tests for exact Oreslang cross-stack fixture identity admission."""

import copy
import json
import unittest

from scripts.verify_oreslang_portability import (
    EXPECTED_FIXTURE_REPOS,
    validate_fixture_repositories,
)
from scripts.render_runtime_fixture_gitmodules import render_oreslang_modules


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
        bad[0]["unverified_runtime_success"] = True
        self.assertTrue(validate_fixture_repositories(bad))

    def test_missing_or_malformed_immutable_sha_is_rejected(self):
        for malformed in (None, "", "main", "0" * 39, "0" * 41, "G" * 40):
            bad = copy.deepcopy(self.inventory)
            bad[0]["sha"] = malformed
            self.assertTrue(validate_fixture_repositories(bad), str(malformed))
        bad = copy.deepcopy(self.inventory)
        del bad[0]["sha"]
        self.assertTrue(validate_fixture_repositories(bad))

    def test_all_ten_stacks_use_same_reviewed_source_commits(self):
        with open("shared/oreslang-portability.json", encoding="utf-8") as f:
            manifest = json.load(f)
        sources = {r["name"]: r["sha"] for r in manifest["fixture_repositories"]}
        self.assertEqual(len(manifest["targets"]), 10)
        for target in manifest["targets"]:
            self.assertEqual(target["status"], "complete")
            self.assertEqual(len(target["pins"]), 2)
            self.assertEqual(
                {p["repository"]: p["sha"] for p in target["pins"]},
                sources,
                target["stack"],
            )
            for pin in target["pins"]:
                self.assertEqual(
                    pin["path"],
                    "stacks/{}/projects/oreslang-portability/repos/{}".format(
                        target["stack"], pin["repository"]
                    ),
                )

    def test_renderer_never_trusts_tampered_manifest_metadata(self):
        with open("shared/oreslang-portability.json", encoding="utf-8") as f:
            original = json.load(f)
        with open("shared/stack-catalog.json", encoding="utf-8") as f:
            catalog = json.load(f)

        output = render_oreslang_modules(original, catalog)
        self.assertEqual(output.count('[submodule "oreslang-portability--'), 20)
        self.assertIn("ores-dummy-org-oreslang-stack-api-server.ores.git", output)
        self.assertNotIn("ores-dummy-org-oreslang-stack-infra.git", output)

        bad = copy.deepcopy(original)
        bad["targets"][0]["pins"][0]["repository"] = "../../other-repo"
        with self.assertRaises(ValueError):
            render_oreslang_modules(bad, catalog)

        bad = copy.deepcopy(original)
        bad["targets"][0]["pins"][0]["sha"] = "0" * 40
        with self.assertRaises(ValueError):
            render_oreslang_modules(bad, catalog)

        bad = copy.deepcopy(original)
        bad["targets"][0]["pins"][0]["path"] = "/tmp/escaped"
        with self.assertRaises(ValueError):
            render_oreslang_modules(bad, catalog)

        bad = copy.deepcopy(original)
        bad["targets"][0]["runtime_certified"] = True
        with self.assertRaises(ValueError):
            render_oreslang_modules(bad, catalog)

        bad = copy.deepcopy(original)
        bad["targets"][0]["pins"].pop()
        with self.assertRaises(ValueError):
            render_oreslang_modules(bad, catalog)

        bad = copy.deepcopy(original)
        bad["fixture_repositories"][0]["name"] = "ores-dummy-org-oreslang-stack-lambdas"
        with self.assertRaises(ValueError):
            render_oreslang_modules(bad, catalog)

    def test_invalid_identity_type_fails(self):
        self.assertTrue(validate_fixture_repositories([None, self.inventory[1]]))


if __name__ == "__main__":
    unittest.main()
