from __future__ import annotations

import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_private_repo_read_access",
    ROOT / "scripts" / "verify_private_repo_read_access.py",
)
assert SPEC is not None and SPEC.loader is not None
access = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(access)


class PrivateRepoReadAccessTests(unittest.TestCase):
    def make_root(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        (root / "tools").mkdir(parents=True)
        (root / ".gitmodules").write_text(
            """
[submodule "one"]
    path = stacks/a/repos/app
    url = https://github.com/ores-dummy-org-1/app.git
[submodule "duplicate"]
    path = stacks/b/repos/app
    url = https://github.com/ores-dummy-org-1/app.git
[submodule "two"]
    path = stacks/a/repos/.github
    url = git@github.com:ores-dummy-org-1/.github.git
""".lstrip()
        )
        (root / "tools" / "toolchain.lock.json").write_text(
            json.dumps(
                {
                    "schema": "ores.comparisons.toolchain-lock/v1",
                    "tools": {
                        "ores-compose": {
                            "repository": "https://github.com/ORESoftware/ores-compose",
                            "commit": "1" * 40,
                        },
                        "zed-cli": {
                            "repository": "https://github.com/zed-pkg/zed-cli.git",
                            "commit": "2" * 40,
                        },
                    },
                }
            )
            + "\n"
        )
        return temp, root

    def test_discovers_and_deduplicates_governed_repositories(self) -> None:
        temp, root = self.make_root()
        self.addCleanup(temp.cleanup)
        repositories = access.required_repositories(root)
        self.assertEqual(
            repositories,
            {
                "ORESoftware/ores-compose": "https://github.com/ORESoftware/ores-compose",
                "ores-dummy-org-1/.github": "https://github.com/ores-dummy-org-1/.github",
                "ores-dummy-org-1/app": "https://github.com/ores-dummy-org-1/app",
                "zed-pkg/zed-cli": "https://github.com/zed-pkg/zed-cli",
            },
        )

    def test_non_github_repository_is_rejected(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "unsupported non-GitHub"):
            access.canonical_repository("https://example.com/owner/repo")

    def test_probe_keeps_secret_out_of_process_arguments(self) -> None:
        token = "github_pat_TEST_SECRET"
        completed = subprocess.CompletedProcess(args=[], returncode=0)
        with mock.patch.object(access.subprocess, "run", return_value=completed) as run:
            access.probe_repository(
                "private/repo",
                "https://github.com/private/repo",
                token,
                2,
            )
        args, kwargs = run.call_args
        rendered_args = " ".join(args[0])
        self.assertNotIn(token, rendered_args)
        self.assertEqual(
            args[0],
            [
                "git",
                "ls-remote",
                "--exit-code",
                "https://github.com/private/repo",
                "HEAD",
            ],
        )
        self.assertNotIn(token, kwargs["env"]["GIT_CONFIG_KEY_0"])
        self.assertNotIn(token, kwargs["env"]["GIT_CONFIG_VALUE_0"])
        self.assertIn("AUTHORIZATION: basic ", kwargs["env"]["GIT_CONFIG_VALUE_0"])

    def test_failure_message_never_contains_secret(self) -> None:
        token = "github_pat_DO_NOT_PRINT"
        completed = subprocess.CompletedProcess(args=[], returncode=128)
        with mock.patch.object(access.subprocess, "run", return_value=completed):
            with self.assertRaises(RuntimeError) as caught:
                access.probe_repository(
                    "private/repo",
                    "https://github.com/private/repo",
                    token,
                    2,
                )
        self.assertNotIn(token, str(caught.exception))
        self.assertIn("private/repo", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
