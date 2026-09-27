from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECKOUT = "uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262"


class RuntimeWorkflowSourceBindingTests(unittest.TestCase):
    def assert_source_bound_checkouts(self, relative: str) -> None:
        source = (ROOT / relative).read_text()
        lines = source.splitlines()
        indexes = [index for index, line in enumerate(lines) if CHECKOUT in line]
        self.assertTrue(indexes, f"{relative} has no governed checkout")
        for index in indexes:
            window = "\n".join(lines[index : index + 8])
            self.assertIn(
                "repository: ${{ job.workflow_repository }}",
                window,
                f"{relative}:{index + 1} checkout is not bound to workflow repository",
            )
            self.assertIn(
                "ref: ${{ job.workflow_sha }}",
                window,
                f"{relative}:{index + 1} checkout is not bound to workflow SHA",
            )
            self.assertIn(
                "persist-credentials: false",
                window,
                f"{relative}:{index + 1} checkout persists credentials",
            )

    def test_runtime_all_checkouts_are_bound_to_called_workflow_source(self) -> None:
        self.assert_source_bound_checkouts(".github/workflows/runtime-all-18.yml")

    def test_runtime_project_checkout_is_bound_to_called_workflow_source(self) -> None:
        self.assert_source_bound_checkouts(".github/workflows/runtime-project.yml")

    def test_runtime_all_declares_explicit_reusable_secret_contract(self) -> None:
        source = (ROOT / ".github/workflows/runtime-all-18.yml").read_text()
        self.assertIn("workflow_call:", source)
        self.assertIn("COMPARISON_REPO_READ_TOKEN:", source)
        self.assertIn(
            "description: Read-only credential covering every governed private runtime repository.",
            source,
        )


if __name__ == "__main__":
    unittest.main()
