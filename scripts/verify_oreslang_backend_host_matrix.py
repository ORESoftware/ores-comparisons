#!/usr/bin/env python3
"""Fail-closed backend x host admission for Oreslang Stack."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHA_RE = re.compile(r"[0-9a-f]{40}\Z")

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
            dev = backend.get("development_only")
            if dev != {
                "assembly_load_context": {
                    "security_boundary": False,
                    "strict_nativeaot": False,
                }
            }:
                bad("dotnet-nativeaot: AssemblyLoadContext must remain development-only/non-security")
            for host in hosts:
                if host.get("stack") == "scintilla-run" and host.get("untrusted_requires_os_sandbox") is not True:
                    bad("dotnet-nativeaot: untrusted worker must require OS sandbox")

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
