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
