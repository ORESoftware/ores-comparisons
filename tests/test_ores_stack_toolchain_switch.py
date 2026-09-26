from __future__ import annotations

import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path

from scripts.ores_stack_toolchain_switch import Toolchain, ToolchainManager


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def executable(path: Path, exit_code: int) -> None:
    path.write_text(f"#!/bin/sh\nexit {exit_code}\n", encoding="utf-8")
    path.chmod(0o755)


class OresStackToolchainSwitchTest(unittest.TestCase):
    def test_failed_upgrade_restores_previous_toolchain_without_project_or_cache_corruption(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manager = ToolchainManager(root / "toolchains")
            stable_binary = root / "ores-stack-stable"
            candidate_binary = root / "ores-stack-candidate"
            executable(stable_binary, 0)
            executable(candidate_binary, 23)

            stable = Toolchain("0.1.0-stable", stable_binary, digest(stable_binary))
            candidate = Toolchain("0.2.0-bad", candidate_binary, digest(candidate_binary))

            project = root / "project"
            project.mkdir()
            config = project / ".ores-stack.toml"
            config.write_bytes(b"version = 1\nmode = \"server\"\n")
            before_config = config.read_bytes()

            self.assertTrue(manager.activate(stable))
            stable_cache = manager.cache_root / stable.identity
            stable_cache.joinpath("artifact").write_text("stable-cache", encoding="utf-8")

            self.assertFalse(
                manager.activate(
                    candidate,
                    smoke_argv=[str(manager.active_path), "--version"],
                    smoke_cwd=project,
                )
            )

            self.assertEqual(config.read_bytes(), before_config)
            self.assertEqual(manager.active_path.resolve(), stable_binary.resolve())
            state = json.loads(manager.state_path.read_text(encoding="utf-8"))
            self.assertEqual(state["current"]["identity"], stable.identity)
            self.assertIsNone(state["previous"])
            self.assertEqual(
                stable_cache.joinpath("artifact").read_text(encoding="utf-8"),
                "stable-cache",
            )
            self.assertTrue((manager.cache_root / candidate.identity).is_dir())

    def test_digest_mismatch_never_changes_active_toolchain(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manager = ToolchainManager(root / "toolchains")
            stable_binary = root / "stable"
            candidate_binary = root / "candidate"
            executable(stable_binary, 0)
            executable(candidate_binary, 0)

            stable = Toolchain("stable", stable_binary, digest(stable_binary))
            self.assertTrue(manager.activate(stable))
            before_state = manager.state_path.read_bytes()
            before_target = manager.active_path.resolve()

            bad = Toolchain("candidate", candidate_binary, "0" * 64)
            with self.assertRaises(ValueError):
                manager.activate(bad)

            self.assertEqual(manager.state_path.read_bytes(), before_state)
            self.assertEqual(manager.active_path.resolve(), before_target)

    @unittest.skipUnless(hasattr(os, "symlink"), "requires symlink support")
    def test_toolchain_binary_symlink_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            real = root / "real"
            link = root / "link"
            executable(real, 0)
            link.symlink_to(real)
            with self.assertRaises(ValueError):
                ToolchainManager.verify_toolchain(
                    Toolchain("linked", link, digest(real))
                )


if __name__ == "__main__":
    unittest.main()
