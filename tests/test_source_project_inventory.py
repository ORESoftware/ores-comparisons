"""Mutation tests for source-stack ownership and remote completeness gates."""

from __future__ import annotations

import copy
import json
import unittest

from scripts.verify_source_project_inventory import (
    CATALOG,
    INVENTORY,
    compare_remote,
    validate,
)


class SourceProjectInventoryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads(CATALOG.read_text())
        cls.inventory = json.loads(INVENTORY.read_text())

    def test_current_snapshot_is_valid(self):
        self.assertEqual(validate(self.catalog, self.inventory), [])

    def test_duplicate_project_assignment_fails(self):
        altered = copy.deepcopy(self.inventory)
        altered["stacks"][0]["repositories"].append(
            copy.deepcopy(altered["stacks"][0]["repositories"][0])
        )
        self.assertTrue(any("duplicate repository" in e for e in validate(self.catalog, altered)))

    def test_cross_stack_reassignment_fails(self):
        altered = copy.deepcopy(self.inventory)
        altered["stacks"][0]["repositories"][0]["repository"] = "ores-stack/ores-stack-cli"
        self.assertTrue(any("another stack owner" in e for e in validate(self.catalog, altered)))

    def test_missing_native_project_fails_floor(self):
        altered = copy.deepcopy(self.inventory)
        altered["stacks"][0]["repositories"].pop()
        self.assertTrue(any("missing minimum native" in e for e in validate(self.catalog, altered)))

    def test_unknown_or_dropped_stack_fails(self):
        altered = copy.deepcopy(self.inventory)
        altered["stacks"].pop()
        self.assertTrue(any("expected scoped stacks" in e for e in validate(self.catalog, altered)))

    def test_unknown_category_fails(self):
        altered = copy.deepcopy(self.inventory)
        altered["stacks"][0]["repositories"][0]["category"] = "runtime-tested"
        self.assertTrue(any("unknown source project category" in e for e in validate(self.catalog, altered)))

    def test_unexpected_upstream_repo_identified_as_unassigned(self):
        observed = self._matching_observed()
        observed["litegraph"].append(
            {"full_name": "litegraph/new-worker", "default_branch": "main", "visibility": "private"}
        )
        self.assertTrue(any("UNASSIGNED SOURCE PROJECT litegraph/new-worker" in e
                            for e in compare_remote(self.inventory, observed)))

    def test_upstream_disappeared_is_not_silent(self):
        observed = self._matching_observed()
        observed["ores-stack"].pop()
        self.assertTrue(any("MISSING REMOTE PROJECT" in e
                            for e in compare_remote(self.inventory, observed)))

    def test_upstream_default_branch_drift(self):
        observed = self._matching_observed()
        observed["litegraph"][0]["default_branch"] = "dev"
        self.assertTrue(any("default_branch drift" in e
                            for e in compare_remote(self.inventory, observed)))

    def test_matching_remote_has_no_errors(self):
        self.assertEqual(compare_remote(self.inventory, self._matching_observed()), [])

    def _matching_observed(self):
        return {
            stack["github_owner"]: [
                {"full_name": item["repository"],
                 "default_branch": item["default_branch"],
                 "visibility": item["visibility"]}
                for item in stack["repositories"]
            ]
            for stack in self.inventory["stacks"]
        }


if __name__ == "__main__":
    unittest.main()
