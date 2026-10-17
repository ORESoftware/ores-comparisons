#!/usr/bin/env python3
"""Fail-closed authority for the Oreslang .NET ORW1 wire protocol."""
from __future__ import annotations

import json
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHA_RE = re.compile(r"[0-9a-f]{40}\Z")
MAGIC = 0x4F525731
VERSION = 1
HEADER_BYTES = 28
KINDS = {
    "ready": 1,
    "invoke": 2,
    "cancel": 3,
    "response": 4,
    "error": 5,
    "shutdown": 6,
}
EXPECTED_DOTNET_HEAD = "f3aed954f00ae5e75edfef42b70c253e96ddd99b"
EXPECTED_SCINTILLA_HEAD = "704c9322334e4da6cfb82a08571c042843d5c43e"


def encode(kind: str, request_id: int, deadline: int, payload: str) -> bytes:
    body = payload.encode("utf-8", errors="strict")
    return struct.pack(
        ">IBBHQqi",
        MAGIC,
        VERSION,
        KINDS[kind],
        0,
        request_id,
        deadline,
        len(body),
    ) + body


def validate(m: object) -> list[str]:
    errors: list[str] = []
    def bad(message: str) -> None:
        errors.append(message)

    if not isinstance(m, dict):
        return ["ORW1 contract must be an object"]
    if m.get("schema") != "ores.comparisons.oreslang-orw1/v1":
        bad("wrong ORW1 schema")
    if m.get("protocol") != "ORW1" or m.get("version") != VERSION:
        bad("wrong ORW1 identity/version")
    if m.get("byte_order") != "big-endian" or m.get("header_bytes") != HEADER_BYTES:
        bad("ORW1 byte order/header width drifted")
    if m.get("frame_kinds") != KINDS:
        bad("ORW1 frame-kind numbering drifted")

    expected_fields = [
        {"name": "magic", "type": "u32", "bytes": 4, "value_hex": "4f525731"},
        {"name": "version", "type": "u8", "bytes": 1, "value": 1},
        {"name": "kind", "type": "u8", "bytes": 1},
        {"name": "flags", "type": "u16", "bytes": 2, "required": 0},
        {"name": "request_id", "type": "u64", "bytes": 8},
        {"name": "deadline_unix_ms", "type": "i64", "bytes": 8},
        {"name": "payload_length", "type": "i32", "bytes": 4},
    ]
    if m.get("fields") != expected_fields:
        bad("ORW1 header fields drifted")

    expected_constraints = {
        "payload_encoding": "strict-utf8",
        "max_payload_bytes": 1048576,
        "max_invocation_ms": 30000,
        "request_ids": "strictly-increasing-per-worker-generation",
        "error_code": "1..96 lowercase ASCII letters/digits/hyphen",
        "ready_payload": "oreslang-dotnet-worker/1",
        "nonzero_flags": "rejected",
        "unknown_kind": "rejected",
    }
    if m.get("constraints") != expected_constraints:
        bad("ORW1 bounds/admission rules drifted")

    vectors = m.get("vectors")
    expected_names = {
        "ready",
        "invoke-empty",
        "response-42",
        "error-guest-failure",
        "shutdown",
    }
    if not isinstance(vectors, list) or {v.get("name") for v in vectors if isinstance(v, dict)} != expected_names:
        bad("ORW1 canonical vector roster drifted")
    else:
        for vector in vectors:
            if set(vector) != {
                "name", "kind", "request_id", "deadline_unix_ms", "payload", "hex"
            }:
                bad(f"{vector.get('name')}: vector fields drifted")
                continue
            try:
                actual = encode(
                    vector["kind"],
                    vector["request_id"],
                    vector["deadline_unix_ms"],
                    vector["payload"],
                ).hex()
            except (KeyError, TypeError, ValueError, struct.error, UnicodeError):
                bad(f"{vector.get('name')}: vector cannot be encoded")
                continue
            if actual != vector["hex"]:
                bad(f"{vector['name']}: canonical bytes drifted")

    implementations = m.get("implementations")
    if not isinstance(implementations, dict):
        bad("ORW1 implementation pins missing")
    else:
        dotnet = implementations.get("dotnet")
        scintilla = implementations.get("scintilla")
        expected_dotnet = {
            "repository": "ores-truffle-oreslang/oreslang-source.net",
            "pr": 81,
            "head": EXPECTED_DOTNET_HEAD,
            "role": "producer-consumer-worker",
            "execution_status": "candidate",
        }
        expected_scintilla = {
            "repository": "scintilla-run/gleam-lambda-runner",
            "pr": 55,
            "head": EXPECTED_SCINTILLA_HEAD,
            "role": "beam-codec-and-disconnected-port-worker",
            "execution_status": "not-executed",
        }
        if dotnet != expected_dotnet:
            bad("ORW1 .NET implementation pin/status drifted")
        if scintilla != expected_scintilla:
            bad("ORW1 Scintilla implementation pin/status drifted")
        for name, entry in (("dotnet", dotnet), ("scintilla", scintilla)):
            if isinstance(entry, dict):
                head = entry.get("head")
                if not isinstance(head, str) or not SHA_RE.fullmatch(head):
                    bad(f"ORW1 {name} head is not an immutable Git SHA")

    if m.get("live_runtime_registered") is not False:
        bad("ORW1 live runtime cannot be registered without execution proof")
    if m.get("status") != "candidate":
        bad("ORW1 status cannot be promoted without runtime proof")
    if m.get("runtime_receipt") is not None:
        bad("ORW1 runtime receipt must remain null until exact-head execution")

    return errors


def verify() -> list[str]:
    return validate(json.loads(
        (ROOT / "shared/oreslang-dotnet-orw1.json").read_text(encoding="utf-8")
    ))


if __name__ == "__main__":
    failures = verify()
    if failures:
        print("Oreslang ORW1 contract FAILED")
        for failure in failures:
            print(" -", failure)
        raise SystemExit(1)
    print("Oreslang ORW1 contract OK: canonical bytes and disconnected implementation pins remain fail-closed")
