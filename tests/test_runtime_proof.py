from __future__ import annotations

import importlib.util
import json
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "prove_runtime_project",
    ROOT / "scripts" / "prove_runtime_project.py",
)
assert SPEC is not None and SPEC.loader is not None
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class RuntimeProofTests(unittest.TestCase):
    def make_root(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        (root / "shared").mkdir(parents=True)
        (root / "shared" / "project-matrix.json").write_text(
            json.dumps(
                {
                    "schema": "ores.comparisons.project-matrix/v1",
                    "projects": [
                        {
                            "stack": "beamscale",
                            "scenario": "cached-rpc",
                            "contracts": True,
                            "benchmark": True,
                        }
                    ],
                }
            )
            + "\n"
        )
        manifest = (
            root
            / "stacks"
            / "beamscale"
            / "projects"
            / "cached-rpc"
            / "repos"
            / ".github"
            / ".ores-compose.yaml"
        )
        manifest.parent.mkdir(parents=True)
        manifest.write_text("schema_version: ores.compose.v1\n")
        return temp, root

    def write_compose(self, root: Path, body: str) -> Path:
        path = root / "fake-ores-compose"
        path.write_text("#!/usr/bin/env python3\n" + textwrap.dedent(body))
        path.chmod(0o755)
        return path

    def test_ready_event_then_sigint_is_a_passing_runtime_receipt(self) -> None:
        temp, root = self.make_root()
        self.addCleanup(temp.cleanup)
        compose = self.write_compose(
            root,
            """
            import json
            import signal
            import sys
            import time

            def stop(_signal, _frame):
                raise SystemExit(0)

            signal.signal(signal.SIGINT, stop)
            print(json.dumps({"event": "compose_ready", "project": "fake", "replicas": 2}), flush=True)
            while True:
                time.sleep(0.05)
            """,
        )
        receipt = runtime.prove_runtime(
            root=root,
            stack="beamscale",
            scenario="cached-rpc",
            compose=compose,
            ready_timeout=2,
            shutdown_timeout=2,
            output_dir=root / "artifacts",
        )
        data = json.loads(receipt.read_text())
        self.assertEqual(data["status"], "passed")
        self.assertTrue(data["composeReady"])
        self.assertEqual(data["returnCode"], 0)
        self.assertEqual(data["readyEvent"]["event"], "compose_ready")

    def test_exit_before_ready_fails_closed_and_records_return_code(self) -> None:
        temp, root = self.make_root()
        self.addCleanup(temp.cleanup)
        compose = self.write_compose(
            root,
            """
            import sys
            print("startup failed", flush=True)
            raise SystemExit(3)
            """,
        )
        with self.assertRaisesRegex(RuntimeError, "before compose_ready"):
            runtime.prove_runtime(
                root=root,
                stack="beamscale",
                scenario="cached-rpc",
                compose=compose,
                ready_timeout=2,
                shutdown_timeout=1,
                output_dir=root / "artifacts",
            )
        data = json.loads((root / "artifacts" / "beamscale-cached-rpc.json").read_text())
        self.assertEqual(data["status"], "failed")
        self.assertFalse(data["composeReady"])
        self.assertEqual(data["returnCode"], 3)

    def test_ready_timeout_fails_closed_and_stops_child(self) -> None:
        temp, root = self.make_root()
        self.addCleanup(temp.cleanup)
        compose = self.write_compose(
            root,
            """
            import signal
            import time

            def stop(_signal, _frame):
                raise SystemExit(0)

            signal.signal(signal.SIGINT, stop)
            print("still booting", flush=True)
            while True:
                time.sleep(0.05)
            """,
        )
        with self.assertRaisesRegex(RuntimeError, "timed out"):
            runtime.prove_runtime(
                root=root,
                stack="beamscale",
                scenario="cached-rpc",
                compose=compose,
                ready_timeout=0.2,
                shutdown_timeout=1,
                output_dir=root / "artifacts",
            )
        data = json.loads((root / "artifacts" / "beamscale-cached-rpc.json").read_text())
        self.assertEqual(data["status"], "failed")
        self.assertFalse(data["composeReady"])
        self.assertIn("timed out", data["error"])


if __name__ == "__main__":
    unittest.main()
