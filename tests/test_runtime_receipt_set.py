from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_runtime_receipts",
    ROOT / "scripts" / "verify_runtime_receipts.py",
)
assert SPEC is not None and SPEC.loader is not None
proofset = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(proofset)


class RuntimeProofSetTests(unittest.TestCase):
    REVISION = "a" * 40

    def make_root(self) -> tuple[tempfile.TemporaryDirectory[str], Path, Path]:
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        (root / "shared").mkdir(parents=True)
        (root / "tools").mkdir(parents=True)
        artifacts = root / "artifacts"
        artifacts.mkdir()

        projects = [
            {"stack": "beamscale", "scenario": "cached-rpc"},
            {"stack": "ores-stack", "scenario": "http-observability"},
        ]
        (root / "shared" / "project-matrix.json").write_text(
            json.dumps(
                {
                    "schema": "ores.comparisons.project-matrix/v1",
                    "projects": projects,
                },
                sort_keys=True,
            )
            + "\n"
        )
        ledger_entries = []
        for item in projects:
            stack = item["stack"]
            scenario = item["scenario"]
            ledger_entries.append(
                {
                    "stack": stack,
                    "scenario": scenario,
                    "path": f"stacks/{stack}/projects/{scenario}/repos/.github",
                    "commit": ("1" if stack == "beamscale" else "2") * 40,
                }
            )
        (root / "shared" / "dummy-org-gitlinks.json").write_text(
            json.dumps(
                {
                    "schema": "ores.comparisons.dummy-org-gitlinks/v1",
                    "entries": ledger_entries,
                },
                sort_keys=True,
            )
            + "\n"
        )
        (root / "tools" / "toolchain.lock.json").write_text(
            json.dumps(
                {
                    "schema": "ores.comparisons.toolchain-lock/v1",
                    "tools": {
                        "ores-compose": {
                            "commit": "3" * 40,
                        }
                    },
                },
                sort_keys=True,
            )
            + "\n"
        )
        return temp, root, artifacts

    def write_receipts(self, root: Path, artifacts: Path) -> None:
        matrix_sha = proofset.sha256_file(root / "shared" / "project-matrix.json")
        ledger_sha = proofset.sha256_file(root / "shared" / "dummy-org-gitlinks.json")
        lock_sha = proofset.sha256_file(root / "tools" / "toolchain.lock.json")
        for stack, scenario in proofset.expected_projects(root):
            manifest = proofset.expected_manifest(stack, scenario)
            receipt = {
                "schema": "ores.comparisons.runtime-proof/v2",
                "stack": stack,
                "scenario": scenario,
                "revision": self.REVISION,
                "projectMatrixSha256": matrix_sha,
                "gitlinkLedgerSha256": ledger_sha,
                "toolchainLockSha256": lock_sha,
                "oresComposeCommit": "3" * 40,
                "composeBinarySha256": "4" * 64,
                "gitlinks": proofset.expected_gitlinks(root, stack, scenario),
                "manifest": manifest,
                "manifestSha256": "5" * 64,
                "command": ["ores-compose", "up", manifest],
                "status": "passed",
                "composeReady": True,
                "returnCode": 0,
                "durationSeconds": 1.25,
                "readyEvent": {
                    "event": "compose_ready",
                    "project": scenario,
                    "replicas": 2,
                },
            }
            (artifacts / f"{stack}-{scenario}.json").write_text(
                json.dumps(receipt, sort_keys=True) + "\n"
            )

    def test_complete_exact_receipt_set_passes(self) -> None:
        temp, root, artifacts = self.make_root()
        self.addCleanup(temp.cleanup)
        self.write_receipts(root, artifacts)
        summary = proofset.verify_receipts(
            root=root,
            directory=artifacts,
            expected_revision=self.REVISION,
        )
        self.assertEqual(summary["status"], "passed")
        self.assertEqual(summary["count"], 2)
        self.assertEqual(summary["revision"], self.REVISION)
        self.assertEqual(len(summary["receipts"]), 2)

    def test_missing_receipt_fails_closed(self) -> None:
        temp, root, artifacts = self.make_root()
        self.addCleanup(temp.cleanup)
        self.write_receipts(root, artifacts)
        (artifacts / "beamscale-cached-rpc.json").unlink()
        with self.assertRaisesRegex(RuntimeError, "receipt set mismatch"):
            proofset.verify_receipts(
                root=root,
                directory=artifacts,
                expected_revision=self.REVISION,
            )

    def test_tampered_gitlink_fails_closed(self) -> None:
        temp, root, artifacts = self.make_root()
        self.addCleanup(temp.cleanup)
        self.write_receipts(root, artifacts)
        path = artifacts / "beamscale-cached-rpc.json"
        receipt = json.loads(path.read_text())
        key = next(iter(receipt["gitlinks"]))
        receipt["gitlinks"][key] = "f" * 40
        path.write_text(json.dumps(receipt) + "\n")
        with self.assertRaisesRegex(RuntimeError, "gitlinks do not match"):
            proofset.verify_receipts(
                root=root,
                directory=artifacts,
                expected_revision=self.REVISION,
            )

    def test_nonpassing_receipt_fails_closed(self) -> None:
        temp, root, artifacts = self.make_root()
        self.addCleanup(temp.cleanup)
        self.write_receipts(root, artifacts)
        path = artifacts / "ores-stack-http-observability.json"
        receipt = json.loads(path.read_text())
        receipt["status"] = "failed"
        path.write_text(json.dumps(receipt) + "\n")
        with self.assertRaisesRegex(RuntimeError, "runtime status is not passed"):
            proofset.verify_receipts(
                root=root,
                directory=artifacts,
                expected_revision=self.REVISION,
            )


if __name__ == "__main__":
    unittest.main()
