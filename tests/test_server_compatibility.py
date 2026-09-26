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


    def test_dependency_outage_removes_readiness_without_restart(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["runtimeSafety"]["availability"]["dependencyOutageTriggersRestart"] = True
        self.assertIn("dependency outage must not trigger restart", validate(broken))

    def test_drain_deadline_is_bounded(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["runtimeSafety"]["draining"]["shutdownDeadlineMs"] = 0
        self.assertIn(
            "draining shutdownDeadlineMs must be between 1 and 300000",
            validate(broken),
        )

    def test_deadline_propagates_to_database_rpc_and_children(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["runtimeSafety"]["deadlines"]["downstreamRpcPropagates"] = False
        self.assertIn(
            "runtimeSafety.deadlines.downstreamRpcPropagates must be true",
            validate(broken),
        )
        broken = copy.deepcopy(self.valid)
        broken["runtimeSafety"]["deadlines"]["spawnedChildPropagates"] = False
        self.assertIn(
            "runtimeSafety.deadlines.spawnedChildPropagates must be true",
            validate(broken),
        )

    def test_request_buffer_limits_are_positive_and_preallocation(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["runtimeSafety"]["bufferLimits"]["bodyBytes"] = 0
        self.assertIn(
            "runtimeSafety.bufferLimits.bodyBytes must be a positive integer",
            validate(broken),
        )
        broken = copy.deepcopy(self.valid)
        broken["runtimeSafety"]["bufferLimits"]["enforceBeforeExpensiveAllocation"] = False
        self.assertIn(
            "buffer limits must be enforced before expensive allocation",
            validate(broken),
        )


    def test_public_errors_are_equivalent_and_do_not_leak_internals(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["edgeSafety"]["publicErrors"]["standaloneFunctionEquivalent"] = False
        self.assertIn(
            "standalone and function public errors must be equivalent",
            validate(broken),
        )
        broken = copy.deepcopy(self.valid)
        broken["edgeSafety"]["publicErrors"]["internalDiagnosticsExposed"] = True
        self.assertIn(
            "public errors must not expose internal diagnostics",
            validate(broken),
        )

    def test_forwarded_headers_are_trusted_only_at_configured_ingress(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["edgeSafety"]["proxyTrust"]["configuredIngresses"] = []
        self.assertIn(
            "proxyTrust.configuredIngresses must be a non-empty string array",
            validate(broken),
        )
        broken = copy.deepcopy(self.valid)
        broken["edgeSafety"]["proxyTrust"]["untrustedForwardedHeadersIgnored"] = False
        self.assertIn("untrusted forwarded headers must be ignored", validate(broken))

    def test_browser_security_requires_secure_cookies_and_origin_validation(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["edgeSafety"]["browserSecurity"]["secureCookies"] = False
        self.assertIn("browser security requires secure cookies", validate(broken))
        broken = copy.deepcopy(self.valid)
        broken["edgeSafety"]["browserSecurity"]["originValidation"] = False
        self.assertIn("browser security requires origin validation", validate(broken))

    def test_connection_budgets_are_derived_from_replica_count(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["edgeSafety"]["connectionBudgets"]["databaseTotalBudget"] += 1
        self.assertIn(
            "database total budget must equal replicas * pool per replica",
            validate(broken),
        )
        broken = copy.deepcopy(self.valid)
        broken["edgeSafety"]["connectionBudgets"]["outboundHttpPoolPerReplica"] = 101
        self.assertIn(
            "outbound HTTP pool per replica exceeds provider concurrency",
            validate(broken),
        )


    def test_durable_state_cannot_use_instance_filesystem(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["storage"]["durableStateUsesInstanceFilesystem"] = True
        self.assertIn("durable state must not use instance filesystem", validate(broken))

    def test_contract_digest_mismatch_blocks_readiness(self) -> None:
        broken = copy.deepcopy(self.valid)
        broken["contractAdmission"]["generatedClientContractDigest"] = "b" * 64
        self.assertIn("contract digests must agree before readiness", validate(broken))
        broken = copy.deepcopy(self.valid)
        broken["contractAdmission"]["readinessBlockedOnMismatch"] = False
        self.assertIn("contract digest mismatch must block readiness", validate(broken))

if __name__ == "__main__":
    unittest.main()
