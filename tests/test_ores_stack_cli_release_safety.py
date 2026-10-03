from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.verify_ores_stack_cli_release_safety import (
    ERROR_SCHEMA,
    failure_envelope,
    load_contract,
    tree_digest,
    verify_contract,
)


class OresStackCliReleaseSafetyTest(unittest.TestCase):
    def test_checked_in_contract_is_coherent(self) -> None:
        self.assertEqual(verify_contract(load_contract()), [])

    def test_error_catalog_covers_every_primary_command(self) -> None:
        catalog = load_contract()["errorCodes"]
        commands = {item["command"] for item in catalog if item["command"] != "all"}
        self.assertEqual(commands, {"build", "dev", "generate", "verify", "deploy"})

    def test_failure_envelope_is_stable_and_structured(self) -> None:
        value = failure_envelope(
            "ORES.BUILD.EXECUTION_FAILED",
            "build",
            "compiler exited unsuccessfully",
            False,
            {"step": "cargo-build", "status": 101},
        )
        self.assertEqual(value["schema"], ERROR_SCHEMA)
        self.assertEqual(
            set(value),
            {"schema", "code", "command", "message", "retryable", "details"},
        )
        json.dumps(value)

    def test_failure_envelope_rejects_unstable_codes(self) -> None:
        with self.assertRaises(ValueError):
            failure_envelope("build failed", "build", "no", False)

    def test_project_state_digest_changes_on_config_or_cache_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / "tmp/cache").mkdir(parents=True)
            (root / ".ores-stack.toml").write_text("version = 1\n")
            (root / "tmp/cache/sentinel").write_bytes(b"a")
            before = tree_digest(root)
            (root / "tmp/cache/sentinel").write_bytes(b"b")
            self.assertNotEqual(before, tree_digest(root))

    def test_migration_recovery_fixture_never_counts_as_release_proof(self) -> None:
        fixture = load_contract()["recovery"]["migrationFixture"]
        self.assertEqual(fixture["evidenceClass"], "migration-fixture")
        self.assertFalse(fixture["countsAsReleaseProof"])


if __name__ == "__main__":
    unittest.main()
