from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.verify_ores_stack_clean_machine import run_clean_machine


def executable(path: Path, body: str) -> None:
    path.write_text("#!/bin/sh\nset -eu\n" + body + "\n", encoding="utf-8")
    path.chmod(0o755)


class OresStackCleanMachineTest(unittest.TestCase):
    def test_empty_home_and_declared_tool_path_are_enforced(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project = root / "project"
            project.mkdir()
            cli = root / "ores-stack"
            executable(
                cli,
                'test -d "$HOME"; test ! -e "$HOME/.cargo"; git --version >/dev/null; exit 0',
            )
            result = run_clean_machine(
                cli,
                project,
                ["build"],
                allowed_tools=["git"],
            )
            self.assertEqual(result.returncode, 0, result.stderr.decode())

    def test_undeclared_global_tool_dependency_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project = root / "project"
            project.mkdir()
            cli = root / "ores-stack"
            executable(cli, 'python3 -c "print(1)" >/dev/null')
            result = run_clean_machine(
                cli,
                project,
                ["build"],
                allowed_tools=[],
            )
            self.assertNotEqual(result.returncode, 0)

    def test_declared_tool_symlink_is_resolved_but_candidate_symlink_still_is_not(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project = root / "project"
            project.mkdir()
            cli = root / "ores-stack"
            executable(cli, 'cc --version >/dev/null 2>&1 || exit 9')
            # The harness is expected to admit an explicitly declared system tool
            # even when its PATH entry is a symlink (common for cc/cargo/rustc).
            result = run_clean_machine(
                cli,
                project,
                ["build"],
                allowed_tools=["cc"],
            )
            self.assertEqual(result.returncode, 0, result.stderr.decode())

    def test_candidate_cli_symlink_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project = root / "project"
            project.mkdir()
            real = root / "real"
            link = root / "ores-stack"
            executable(real, "exit 0")
            link.symlink_to(real)
            with self.assertRaises(ValueError):
                run_clean_machine(
                    link,
                    project,
                    ["build"],
                    allowed_tools=[],
                )


if __name__ == "__main__":
    unittest.main()
