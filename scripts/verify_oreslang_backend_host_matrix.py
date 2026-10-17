#!/usr/bin/env python3
"""Fail-closed backend x host admission for Oreslang Stack."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHA_RE = re.compile(r"[0-9a-f]{40}\Z")
DOTNET_HEAD = "fcc9a859b548bfec93584ce71ba0f036e9030527"
DOTNET_WORKER_PROTOCOL = {
    "schema": "oreslang.dotnet-worker/v1",
    "status": "candidate",
    "magic": "ORW1",
    "version": 1,
    "max_payload_bytes": 1048576,
    "max_invocation_ms": 30000,
    "scalar_guest_abi": "main() -> i64",
    "lambda_guest_abi": "String -> String",
    "lambda_abi_source_connected": True,
    "lambda_abi_runtime_verified": False,
    "raw_request_payload": True,
    "request_id_admission": "strictly-increasing",
    "unexpected_guest_failure": "opaque-error-and-retire",
    "scintilla_adapter": "candidate-ready-gated",
    "nativeaot_execution_receipt": None,
}

EXPECTED = {
    "graal": {
        "repo": "ores-truffle-oreslang/oreslang-source.java",
        "artifact": "graal-runtime-or-native-image",
        "hosts": {("graal-show", "planned", "graal-polyglot-or-native-isolate")},
    },
    "llvm-native": {
        "repo": "ores-truffle-oreslang/oreslang-source.llvm",
        "artifact": "native",
        "hosts": {
            ("scintilla-run", "planned", "worker-process"),
            ("litegraph", "planned", "host-process-specialized"),
        },
    },
    "llvm-wasm": {
        "repo": "ores-truffle-oreslang/oreslang-source.llvm",
        "artifact": "wasm32-wasi",
        "hosts": {
            ("wasm-xprs", "future-backend", "wasm-instance"),
            ("lunatic-lorry", "future-backend", "wasm-actor-process"),
        },
    },
    "wasm": {
        "repo": "ores-truffle-oreslang/oreslang-source.wasm",
        "artifact": "wasm32-wasi",
        "hosts": {
            ("wasm-xprs", "future-backend", "wasm-instance"),
            ("lunatic-lorry", "future-backend", "wasm-actor-process"),
        },
    },
    "dotnet-nativeaot": {
        "repo": "ores-truffle-oreslang/oreslang-source.net",
        "artifact": "native-aot",
        "hosts": {("scintilla-run", "planned", "worker-process")},
    },
    "javascript": {
        "repo": "ores-truffle-oreslang/oreslang-source.js",
        "artifact": "esm",
        "hosts": {("iso-lattes", "future-backend", "javascript-isolate")},
    },
}


def validate(m: object) -> list[str]:
    errors: list[str] = []
    def bad(s: str) -> None: errors.append(s)

    if not isinstance(m, dict):
        return ["backend-host matrix must be an object"]
    if m.get("schema") != "ores.comparisons.oreslang-backend-host-matrix/v1":
        bad("wrong backend-host schema")
    if m.get("language") != "oreslang":
        bad("wrong language identity")

    rules = m.get("rules")
    expected_rules = {
        "backend_selects_artifact": True,
        "host_selects_execution_environment": True,
        "assembly_load_context_is_security_boundary": False,
        "wasm_requires_wasm_artifact": True,
        "deployment_status": "not-proven",
    }
    if rules != expected_rules:
        bad("backend/host separation or isolation rules drifted")

    backends = m.get("backends")
    if not isinstance(backends, list):
        return errors + ["backends must be an array"]

    seen: set[str] = set()
    for backend in backends:
        if not isinstance(backend, dict) or not isinstance(backend.get("id"), str):
            bad("invalid backend entry")
            continue
        bid = backend["id"]
        if bid in seen:
            bad(f"duplicate backend {bid}")
            continue
        seen.add(bid)
        spec = EXPECTED.get(bid)
        if spec is None:
            bad(f"unreviewed backend {bid}")
            continue
        if backend.get("source_repository") != spec["repo"]:
            bad(f"{bid}: wrong source repository")
        if backend.get("artifact") != spec["artifact"]:
            bad(f"{bid}: wrong artifact")

        hosts = backend.get("hosts")
        if not isinstance(hosts, list):
            bad(f"{bid}: hosts must be an array")
            continue
        actual = set()
        for host in hosts:
            if not isinstance(host, dict):
                bad(f"{bid}: invalid host")
                continue
            actual.add((host.get("stack"), host.get("status"), host.get("isolation")))
            if host.get("status") not in {"planned", "future-backend"}:
                bad(f"{bid}: executable/deployable status is not yet admitted")
        if actual != spec["hosts"]:
            bad(f"{bid}: host matrix drifted")

        if bid in {"llvm-wasm", "wasm"} and backend.get("artifact") != "wasm32-wasi":
            bad(f"{bid}: WASM host requires an actual WASM artifact")

        if bid == "dotnet-nativeaot":
            if backend.get("implementation_pr") != 81:
                bad("dotnet-nativeaot: wrong isolation implementation PR")
            head = backend.get("implementation_head")
            if not isinstance(head, str) or not SHA_RE.fullmatch(head):
                bad("dotnet-nativeaot: implementation head must be immutable Git SHA")
            elif head != DOTNET_HEAD:
                bad("dotnet-nativeaot: isolation/worker candidate head drifted")
            if backend.get("worker_protocol") != DOTNET_WORKER_PROTOCOL:
                bad("dotnet-nativeaot: ORW1 worker protocol candidate drifted")
            dev = backend.get("development_only")
            if dev != {
                "assembly_load_context": {
                    "security_boundary": False,
                    "strict_nativeaot": False,
                }
            }:
                bad("dotnet-nativeaot: AssemblyLoadContext must remain development-only/non-security")
            for host in hosts:
                if host.get("stack") == "scintilla-run":
                    if host.get("untrusted_requires_os_sandbox") is not True:
                        bad("dotnet-nativeaot: untrusted worker must require OS sandbox")
                    if host.get("protocol_adapter") != "candidate-ready-gated":
                        bad("dotnet-nativeaot: Scintilla ORW1 adapter status drifted")
                    candidate = host.get("adapter_candidate")
                    expected_candidate = {
                        "repository": "scintilla-run/gleam-lambda-runner",
                        "pr": 56,
                        "head": "a4ff7e10ec702772b03a4c98660bc0f368a2bb25",
                        "codec": "ORW1-v1",
                        "port_worker": "ready-gated",
                        "runtime_registration_source_connected": True,
                        "raw_request_payload": True,
                        "definition_metadata_in_guest_payload": False,
                        "provider_container_pool_fallback": False,
                        "payload_abi_source_connected": True,
                        "payload_abi_runtime_verified": False,
                        "execution_receipt": None,
                    }
                    if candidate != expected_candidate:
                        bad("dotnet-nativeaot: Scintilla ready-gated adapter candidate drifted")
                    expected_isolation = {
                        "repository": "ORESoftware/ores-proc-isolation-cli",
                        "head": "8f5a70c61abd65c93f5d214f5aad9c888a89af46",
                        "runtime_policy_receipt": None,
                    }
                    if host.get("isolation_policy_candidate") != expected_isolation:
                        bad("dotnet-nativeaot: isolation policy candidate drifted")

    if seen != set(EXPECTED):
        bad(f"backend roster mismatch: {sorted(seen)}")

    return errors


def verify() -> list[str]:
    return validate(json.loads(
        (ROOT / "shared/oreslang-backend-host-matrix.json").read_text()
    ))


if __name__ == "__main__":
    failures = verify()
    if failures:
        print("Oreslang backend-host matrix FAILED")
        for failure in failures:
            print(" -", failure)
        raise SystemExit(1)
    print("Oreslang backend-host matrix OK: artifact/backend and execution-host boundaries remain fail-closed")
