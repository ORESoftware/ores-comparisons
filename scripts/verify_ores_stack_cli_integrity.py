#!/usr/bin/env python3
"""Fail-closed certification helpers for ORES Stack CLI backlog tasks 9-12."""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CONTRACT_PATH = ROOT / "conformance" / "ores-stack-cli" / "integrity-hardening.v1.json"
SCHEMA = "ores.stack.cli-integrity-hardening/v1"
PLUGIN_PROTOCOL = "ores-stack.plugin-command/v1"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
ENV_NAME = re.compile(r"^[A-Z_][A-Z0-9_]*$")

REQUIRED_CACHE_FIELDS = {
    "project_identity",
    "source_digests",
    "lockfile_digest",
    "compiler_identity",
    "generator_identity",
    "target_identity",
    "feature_flags",
    "contract_digests",
}
REQUIRED_CACHE_CASES = {
    "substituted_binary",
    "cross_project_collision",
    "symlink_artifact",
    "size_mismatch",
    "digest_mismatch",
}


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def load_contract() -> dict[str, Any]:
    value = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("integrity hardening contract root must be an object")
    return value


def verify_contract(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if document.get("schema") != SCHEMA:
        errors.append("unsupported integrity hardening schema")

    cache = document.get("cacheIdentity")
    if not isinstance(cache, dict):
        errors.append("cacheIdentity must be an object")
    else:
        fields = set(cache.get("requiredFields", []))
        missing = sorted(REQUIRED_CACHE_FIELDS - fields)
        if missing:
            errors.append(f"cacheIdentity missing required fields: {missing}")

    admission = document.get("cacheAdmission")
    if not isinstance(admission, dict):
        errors.append("cacheAdmission must be an object")
    else:
        cases = set(admission.get("requiredAdversarialCases", []))
        missing = sorted(REQUIRED_CACHE_CASES - cases)
        if missing:
            errors.append(f"cacheAdmission missing adversarial cases: {missing}")

    plugin = document.get("pluginExecution")
    if not isinstance(plugin, dict):
        errors.append("pluginExecution must be an object")
    elif plugin.get("protocolVersion") != PLUGIN_PROTOCOL:
        errors.append("pluginExecution protocol version drift")

    ownership = document.get("regenerationOwnership")
    if not isinstance(ownership, dict):
        errors.append("regenerationOwnership must be an object")
    elif len(ownership.get("requiredRules", [])) < 5:
        errors.append("regenerationOwnership rules are incomplete")

    return errors


def _digest_map(value: Any, label: str) -> dict[str, str]:
    if not isinstance(value, dict) or not value:
        raise ValueError(f"{label} must be a non-empty object")
    out: dict[str, str] = {}
    for key, digest in value.items():
        if not isinstance(key, str) or not key or not isinstance(digest, str) or not HEX64.fullmatch(digest):
            raise ValueError(f"{label} must map non-empty names to lowercase SHA-256 digests")
        out[key] = digest
    return dict(sorted(out.items()))


def canonical_cache_material(spec: dict[str, Any]) -> dict[str, Any]:
    missing = sorted(REQUIRED_CACHE_FIELDS - set(spec))
    if missing:
        raise ValueError(f"cache key missing fields: {missing}")

    for field in ("project_identity", "compiler_identity", "generator_identity", "target_identity"):
        value = spec.get(field)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{field} must be non-empty")

    lockfile = spec.get("lockfile_digest")
    if not isinstance(lockfile, str) or not HEX64.fullmatch(lockfile):
        raise ValueError("lockfile_digest must be lowercase SHA-256")

    features = spec.get("feature_flags")
    if not isinstance(features, list) or any(not isinstance(item, str) or not item for item in features):
        raise ValueError("feature_flags must be an array of non-empty strings")
    if len(set(features)) != len(features):
        raise ValueError("feature_flags must not contain duplicates")

    return {
        "schema": "ores.stack.build-cache-key/v1",
        "project_identity": spec["project_identity"],
        "source_digests": _digest_map(spec["source_digests"], "source_digests"),
        "lockfile_digest": lockfile,
        "compiler_identity": spec["compiler_identity"],
        "generator_identity": spec["generator_identity"],
        "target_identity": spec["target_identity"],
        "feature_flags": sorted(features),
        "contract_digests": _digest_map(spec["contract_digests"], "contract_digests"),
    }


def build_cache_key(spec: dict[str, Any]) -> str:
    material = canonical_cache_material(spec)
    encoded = json.dumps(material, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256_bytes(encoded)


def regeneration_decision(
    *,
    current_bytes: bytes | None,
    desired_bytes: bytes | None,
    generated_marker: bytes,
    last_published_sha256: str | None,
) -> str:
    """Return publish/noop/delete or reject-* without mutating anything."""
    if current_bytes is None:
        return "noop" if desired_bytes is None else "publish"

    if desired_bytes is not None and current_bytes == desired_bytes:
        return "noop"

    if not generated_marker or not current_bytes.startswith(generated_marker):
        return "reject-user-owned"

    if last_published_sha256 is None or not HEX64.fullmatch(last_published_sha256):
        return "reject-unreconciled-generated"

    if sha256_bytes(current_bytes) != last_published_sha256:
        return "reject-modified-generated"

    return "delete" if desired_bytes is None else "publish"


def _safe_relative(path: str) -> bool:
    candidate = Path(path)
    return (
        bool(path)
        and not candidate.is_absolute()
        and all(part not in ("", ".", "..") for part in candidate.parts)
    )


def verify_cache_entry(
    entry: dict[str, Any],
    *,
    artifact_root: Path,
    expected_project_identity: str,
    expected_cache_key: str,
) -> list[str]:
    findings: list[str] = []

    if entry.get("schema") != "ores.stack.build-cache-entry/v1":
        findings.append("cache.unknown-schema")
    if entry.get("project_identity") != expected_project_identity:
        findings.append("cache.cross-project-collision")
    if entry.get("cache_key") != expected_cache_key:
        findings.append("cache.key-mismatch")

    artifact = entry.get("artifact")
    if not isinstance(artifact, dict):
        return findings + ["cache.invalid-artifact-metadata"]

    relative = artifact.get("path")
    digest = artifact.get("sha256")
    size = artifact.get("size_bytes")
    if not isinstance(relative, str) or not _safe_relative(relative):
        return findings + ["cache.unsafe-artifact-path"]
    if not isinstance(digest, str) or not HEX64.fullmatch(digest):
        findings.append("cache.invalid-artifact-digest")
    if not isinstance(size, int) or size < 0:
        findings.append("cache.invalid-artifact-size")

    path = artifact_root / relative
    try:
        st = path.lstat()
    except FileNotFoundError:
        return findings + ["cache.missing-artifact"]

    if stat.S_ISLNK(st.st_mode) or not stat.S_ISREG(st.st_mode):
        return findings + ["cache.artifact-not-regular"]

    resolved_root = artifact_root.resolve()
    resolved = path.resolve()
    try:
        resolved.relative_to(resolved_root)
    except ValueError:
        return findings + ["cache.artifact-path-escape"]

    payload = path.read_bytes()
    if isinstance(size, int) and len(payload) != size:
        findings.append("cache.size-mismatch")
    if isinstance(digest, str) and HEX64.fullmatch(digest) and sha256_bytes(payload) != digest:
        findings.append("cache.digest-mismatch")
    return findings


def validate_plugin_command(spec: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if spec.get("schema") != PLUGIN_PROTOCOL:
        errors.append("plugin.protocol-version")

    executable = spec.get("executable")
    if not isinstance(executable, dict):
        errors.append("plugin.executable-must-be-declared-path")
    else:
        root = executable.get("root")
        path = executable.get("path")
        if root not in {"project", "toolchain"}:
            errors.append("plugin.executable-root")
        if not isinstance(path, str) or not _safe_relative(path):
            errors.append("plugin.executable-path")

    argv = spec.get("argv")
    if not isinstance(argv, list) or any(not isinstance(item, str) for item in argv):
        errors.append("plugin.argv-array")

    allowlist = spec.get("environment_allowlist")
    if (
        not isinstance(allowlist, list)
        or any(not isinstance(item, str) or not ENV_NAME.fullmatch(item) for item in allowlist)
        or len(set(allowlist or [])) != len(allowlist or [])
    ):
        errors.append("plugin.environment-allowlist")

    working = spec.get("working_directory")
    if not isinstance(working, dict):
        errors.append("plugin.working-directory")
    else:
        if working.get("root") not in {"project", "toolchain"}:
            errors.append("plugin.working-directory-root")
        path = working.get("path")
        if not isinstance(path, str) or (path not in {".", ""} and not _safe_relative(path)):
            errors.append("plugin.working-directory-path")

    compatibility = spec.get("compatibility")
    if not isinstance(compatibility, dict):
        errors.append("plugin.compatibility")
    else:
        if compatibility.get("protocol") != PLUGIN_PROTOCOL:
            errors.append("plugin.compatibility-protocol")
        minimum = compatibility.get("min_cli_version")
        maximum = compatibility.get("max_cli_version")
        if not isinstance(minimum, str) or not SEMVER.fullmatch(minimum):
            errors.append("plugin.compatibility-min-cli")
        if maximum is not None and (not isinstance(maximum, str) or not SEMVER.fullmatch(maximum)):
            errors.append("plugin.compatibility-max-cli")

    return errors


def main() -> int:
    errors = verify_contract(load_contract())
    if errors:
        for error in errors:
            print(f"ERROR {error}")
        return 1
    print("ORES Stack CLI integrity hardening contract OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
