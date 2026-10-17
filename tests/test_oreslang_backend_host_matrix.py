"""Mutation coverage for Oreslang backend/host admission."""

import copy
import json
import unittest

from scripts.verify_oreslang_backend_host_matrix import validate


class OreslangBackendHostMatrixTests(unittest.TestCase):
    def setUp(self):
        with open("shared/oreslang-backend-host-matrix.json", encoding="utf-8") as f:
            self.matrix = json.load(f)

    def test_current_matrix_is_accepted(self):
        self.assertEqual(validate(self.matrix), [])

    def mutate_backend(self, name):
        bad = copy.deepcopy(self.matrix)
        return bad, next(x for x in bad["backends"] if x["id"] == name)

    def test_alc_cannot_become_security_boundary(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["development_only"]["assembly_load_context"]["security_boundary"] = True
        self.assertTrue(validate(bad))

    def test_untrusted_dotnet_process_cannot_drop_os_sandbox_requirement(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["hosts"][0]["untrusted_requires_os_sandbox"] = False
        self.assertTrue(validate(bad))

    def test_dotnet_lambda_source_connection_cannot_be_dropped(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["worker_protocol"]["lambda_abi_source_connected"] = False
        self.assertTrue(validate(bad))

    def test_dotnet_lambda_runtime_cannot_be_claimed_verified(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["worker_protocol"]["lambda_abi_runtime_verified"] = True
        self.assertTrue(validate(bad))

    def test_dotnet_nativeaot_receipt_cannot_be_invented(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["worker_protocol"]["nativeaot_execution_receipt"] = {
            "status": "passed"
        }
        self.assertTrue(validate(bad))

    def test_dotnet_lambda_payload_must_remain_raw_request(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["worker_protocol"]["raw_request_payload"] = False
        self.assertTrue(validate(bad))

    def test_dotnet_worker_replay_admission_cannot_weaken(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["worker_protocol"]["request_id_admission"] = "any-nonzero"
        self.assertTrue(validate(bad))

    def test_dotnet_worker_unknown_failure_must_retire(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["worker_protocol"]["unexpected_guest_failure"] = "continue"
        self.assertTrue(validate(bad))

    def test_scintilla_orw1_adapter_cannot_be_claimed_executable(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["hosts"][0]["protocol_adapter"] = "executable"
        self.assertTrue(validate(bad))

    def test_scintilla_orw1_candidate_cannot_drop_payload_source_connection(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["hosts"][0]["adapter_candidate"]["payload_abi_source_connected"] = False
        self.assertTrue(validate(bad))

    def test_scintilla_orw1_candidate_cannot_claim_runtime_verification(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["hosts"][0]["adapter_candidate"]["payload_abi_runtime_verified"] = True
        self.assertTrue(validate(bad))

    def test_scintilla_must_pass_raw_request_without_definition_metadata(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        candidate = backend["hosts"][0]["adapter_candidate"]
        candidate["definition_metadata_in_guest_payload"] = True
        self.assertTrue(validate(bad))

    def test_scintilla_cannot_restore_provider_container_pool_fallback(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["hosts"][0]["adapter_candidate"]["provider_container_pool_fallback"] = True
        self.assertTrue(validate(bad))

    def test_scintilla_orw1_candidate_cannot_invent_execution_receipt(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["hosts"][0]["adapter_candidate"]["execution_receipt"] = {
            "status": "passed"
        }
        self.assertTrue(validate(bad))

    def test_scintilla_orw1_candidate_head_is_exact(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["hosts"][0]["adapter_candidate"]["head"] = "0" * 40
        self.assertTrue(validate(bad))


    def test_scintilla_orw1_candidate_pr_is_exact(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["hosts"][0]["adapter_candidate"]["pr"] = 55
        self.assertTrue(validate(bad))

    def test_dotnet_isolation_policy_receipt_cannot_be_invented(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["hosts"][0]["isolation_policy_candidate"]["runtime_policy_receipt"] = {
            "status": "passed"
        }
        self.assertTrue(validate(bad))

    def test_dotnet_isolation_policy_head_is_exact(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["hosts"][0]["isolation_policy_candidate"]["head"] = "0" * 40
        self.assertTrue(validate(bad))

    def test_dotnet_candidate_head_is_exact(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["implementation_head"] = "0" * 40
        self.assertTrue(validate(bad))

    def test_dotnet_is_nativeaot_not_wasm(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["artifact"] = "wasm32-wasi"
        self.assertTrue(validate(bad))

    def test_wasm_hosts_require_wasm_artifact(self):
        bad, backend = self.mutate_backend("llvm-wasm")
        backend["artifact"] = "native"
        self.assertTrue(validate(bad))

    def test_js_maps_to_iso_lattes(self):
        bad, backend = self.mutate_backend("javascript")
        backend["hosts"][0]["stack"] = "wasm-xprs"
        self.assertTrue(validate(bad))

    def test_graal_maps_to_graal_show(self):
        bad, backend = self.mutate_backend("graal")
        backend["hosts"][0]["stack"] = "scintilla-run"
        self.assertTrue(validate(bad))

    def test_unproven_host_cannot_be_marked_executable(self):
        bad, backend = self.mutate_backend("dotnet-nativeaot")
        backend["hosts"][0]["status"] = "executable"
        self.assertTrue(validate(bad))

    def test_unreviewed_backend_fails(self):
        bad = copy.deepcopy(self.matrix)
        bad["backends"].append({
            "id": "mystery",
            "source_repository": "example/mystery",
            "artifact": "native",
            "hosts": [],
        })
        self.assertTrue(validate(bad))


if __name__ == "__main__":
    unittest.main()
