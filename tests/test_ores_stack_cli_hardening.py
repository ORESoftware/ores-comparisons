from __future__ import annotations

import copy
import unittest

from scripts.verify_ores_stack_cli_hardening import (
    CANONICAL_REPOSITORY,
    REQUIRED_TARGET_KINDS,
    CACHE_PATH,
    OWNERSHIP_PATH,
    PLUGIN_PATH,
    diagnose,
    load_json,
    MATRIX_PATH,
    SCAFFOLD_PATH,
    verify_cache_integrity,
    verify_generator_ownership,
    verify_matrix,
    verify_plugin_execution,
    verify_scaffold_contract,
)


class OresStackCliHardeningTest(unittest.TestCase):
    def setUp(self) -> None:
        self.matrix = load_json(MATRIX_PATH)
        self.scaffold = load_json(SCAFFOLD_PATH)
        self.ownership = load_json(OWNERSHIP_PATH)
        self.cache = load_json(CACHE_PATH)
        self.plugin = load_json(PLUGIN_PATH)

    def test_checked_in_contracts_are_coherent(self) -> None:
        self.assertEqual(verify_matrix(self.matrix), [])
        self.assertEqual(
            verify_scaffold_contract(
                self.scaffold,
                release_ready=bool(self.matrix["releaseAuthorityReady"]),
            ),
            [],
        )
        self.assertEqual(
            {target["kind"] for target in self.matrix["representativeTargets"]},
            REQUIRED_TARGET_KINDS,
        )
        self.assertEqual(verify_generator_ownership(self.ownership), [])
        self.assertEqual(verify_cache_integrity(self.cache), [])
        self.assertEqual(
            verify_plugin_execution(
                self.plugin,
                release_ready=bool(self.matrix["releaseAuthorityReady"]),
            ),
            [],
        )

    def test_ready_release_authority_requires_supported_release(self) -> None:
        broken = copy.deepcopy(self.matrix)
        broken["releaseAuthorityReady"] = True
        self.assertTrue(any("zero supported releases" in item for item in verify_matrix(broken)))

    def test_every_candidate_requires_every_target_result(self) -> None:
        broken = copy.deepcopy(self.matrix)
        broken["results"] = broken["results"][1:]
        self.assertTrue(any("missing result" in item for item in verify_matrix(broken)))

    def test_release_ready_requires_verified_transactional_scaffolding(self) -> None:
        errors = verify_scaffold_contract(self.scaffold, release_ready=True)
        self.assertTrue(any("transactional scaffolding is verified" in item for item in errors))

    def test_cache_contract_rejects_missing_key_dimension(self) -> None:
        broken = copy.deepcopy(self.cache)
        broken["requiredKeyDimensions"] = [
            value for value in broken["requiredKeyDimensions"] if value != "toolchain_identity"
        ]
        self.assertTrue(
            any("incomplete key dimensions" in item for item in verify_cache_integrity(broken))
        )

    def test_generator_ownership_requires_stale_prune_boundary(self) -> None:
        broken = copy.deepcopy(self.ownership)
        broken["rules"] = [
            rule
            for rule in broken["rules"]
            if rule["id"] != "stale-pruning-is-owner-aware"
        ]
        self.assertTrue(
            any("missing rules" in item for item in verify_generator_ownership(broken))
        )

    def test_release_ready_requires_verified_plugin_execution(self) -> None:
        errors = verify_plugin_execution(self.plugin, release_ready=True)
        self.assertTrue(
            any("constrained plugin execution is verified" in item for item in errors)
        )

    def test_diagnostics_detect_all_requested_fleet_failures_without_mutation(self) -> None:
        snapshot = {
            "repository": "https://github.com/ORESoftware/ores-stack",
            "commit": "main",
            "sha256": None,
            "resolvedBinary": "/usr/local/bin/ores-stack",
            "pinnedBinary": "/opt/ores-stack/bin/ores-stack",
            "missingAdapters": ["aws-lambda-http"],
            "staleGenerators": ["rpc-client"],
            "unresolvedDependencies": ["ores-stack-core"],
            "incompatibleConfiguration": ["deploy target requires websocket"],
        }
        before = copy.deepcopy(snapshot)
        codes = {item["code"] for item in diagnose(snapshot)}
        self.assertEqual(snapshot, before)
        self.assertEqual(
            codes,
            {
                "fleet.cli.legacy-repository",
                "fleet.cli.mutable-or-missing-commit",
                "fleet.cli.missing-or-invalid-checksum",
                "fleet.cli.path-shadowed",
                "fleet.cli.missing-adapter",
                "fleet.cli.stale-generator",
                "fleet.cli.unresolved-dependency",
                "fleet.cli.incompatible-configuration",
            },
        )

    def test_clean_diagnostic_snapshot_has_no_findings(self) -> None:
        snapshot = {
            "repository": CANONICAL_REPOSITORY,
            "commit": "a" * 40,
            "sha256": "b" * 64,
            "resolvedBinary": "/opt/ores-stack/bin/ores-stack",
            "pinnedBinary": "/opt/ores-stack/bin/ores-stack",
            "missingAdapters": [],
            "staleGenerators": [],
            "unresolvedDependencies": [],
            "incompatibleConfiguration": [],
        }
        self.assertEqual(diagnose(snapshot), [])


if __name__ == "__main__":
    unittest.main()
