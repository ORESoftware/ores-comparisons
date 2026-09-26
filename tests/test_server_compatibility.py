from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from scripts.verify_server_compatibility import FIXTURES, validate

class ServerCompatibilityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.valid = json.loads((FIXTURES / "valid" / "web-read.json").read_text())

    def test_valid_read_server(self) -> None:
        self.assertEqual(validate(self.valid), [])

    def test_handler_dimension_is_compile_time_required(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["handlerAdmission"]["compileFailDimensions"].remove("failure")
        self.assertIn(
            "handlerAdmission.compileFailDimensions must cover request,response,context,failure",
            validate(broken),
        )

    def test_authoritative_mutation_is_rejected_for_web_read(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["capabilities"]["authoritativeMutation"] = True
        self.assertIn("web-read authoritative mutations must be disabled", validate(broken))

    def test_authoritative_credentials_need_negative_proof(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["configuration"]["forbiddenAuthoritativeCredentials"] = []
        self.assertIn("web-read must deny authoritative credentials", validate(broken))

    def test_health_and_readiness_are_not_collapsed(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["health"]["readinessPath"] = broken["health"]["livenessPath"]
        self.assertIn("liveness and readiness must be distinct", validate(broken))

if __name__ == "__main__":
    unittest.main()
