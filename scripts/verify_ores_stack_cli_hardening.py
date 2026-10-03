#!/usr/bin/env python3
"""Static ORES Stack CLI migration hardening gates and read-only fleet diagnostics."""

from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "conformance" / "ores-stack-cli" / "compatibility-matrix.v1.json"
SCAFFOLD_PATH = ROOT / "conformance" / "ores-stack-cli" / "scaffold-transaction.v1.json"
OWNERSHIP_PATH = ROOT / "conformance" / "ores-stack-cli" / "generator-ownership.v1.json"
CACHE_PATH = ROOT / "conformance" / "ores-stack-cli" / "cache-integrity.v1.json"
PLUGIN_PATH = ROOT / "conformance" / "ores-stack-cli" / "plugin-execution.v1.json"
CANONICAL_REPOSITORY = "https://github.com/ores-stack/ores-stack-cli"
SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REQUIRED_TARGET_KINDS = {"server", "lambda", "infra"}
REQUIRED_SCAFFOLD_CASES = {
    "authored-files-never-overwritten",
    "validation-failure-rolls-back",
    "interrupt-before-publish-preserves-project",
    "private-staging-cleaned",
    "publish-is-single-filesystem-atomic",
}
RESULT_STATES = {"passed", "failed", "blocked", "not-run"}
REQUIRED_OWNERSHIP_RULES = {
    "exact-first-line-marker",
    "handwritten-targets-fail-closed",
    "symlink-targets-rejected",
    "stale-pruning-is-owner-aware",
}
REQUIRED_CACHE_KEY_DIMENSIONS = {
    "schema",
    "transport",
    "abi_identity_sha256",
    "toolchain_identity",
    "target_identity",
    "profile",
    "inputs.path",
    "inputs.sha256",
}
REQUIRED_CACHE_HIT_REQUIREMENTS = {
    "receipt_is_regular_non_symlink_file",
    "receipt_schema_and_digest_shapes_are_valid",
    "receipt_identity_exactly_matches_recomputed_plan",
    "binary_is_regular_non_symlink_file",
    "binary_sha256_matches_receipt",
}
REQUIRED_PLUGIN_REQUIREMENTS = {
    "plugin_executable_must_resolve_to_a_regular_non_symlink_file",
    "plugin_executable_must_be_inside_an_explicit_approved_root",
    "plugin_executable_sha256_must_match_an_immutable_pin",
    "shell_mediation_is_forbidden",
    "working_directory_must_be_confined_to_the_admitted_repository_or_private_staging_root",
    "stdin_must_be_null_unless_the_contract_explicitly_requires_input",
    "control_environment_must_be_cleared_or_allowlisted",
    "plugin_arguments_must_be_structured_argv_not_shell_text",
    "plugin_failure_must_not_publish_partial_generated_state",
}


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: root must be an object")
    return value


def cli_identity(entry: dict[str, Any]) -> str:
    return f"{entry.get('version', '')}@{entry.get('commit', '')}"


def verify_matrix(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if document.get("schema") != "ores.stack.cli-compatibility/v1":
        errors.append("compatibility matrix: unsupported schema")
    if document.get("canonicalRepository") != CANONICAL_REPOSITORY:
        errors.append("compatibility matrix: canonical repository drift")

    ready = document.get("releaseAuthorityReady")
    if not isinstance(ready, bool):
        errors.append("compatibility matrix: releaseAuthorityReady must be boolean")
        ready = False

    supported = document.get("supportedReleases")
    candidates = document.get("migrationCandidates")
    targets = document.get("representativeTargets")
    results = document.get("results")
    if not isinstance(supported, list):
        errors.append("compatibility matrix: supportedReleases must be an array")
        supported = []
    if not isinstance(candidates, list):
        errors.append("compatibility matrix: migrationCandidates must be an array")
        candidates = []
    if not isinstance(targets, list) or not targets:
        errors.append("compatibility matrix: representativeTargets must be non-empty")
        targets = []
    if not isinstance(results, list):
        errors.append("compatibility matrix: results must be an array")
        results = []

    if ready and not supported:
        errors.append("compatibility matrix: release authority cannot be ready with zero supported releases")

    identities: set[str] = set()
    all_cli_entries: list[dict[str, Any]] = []
    for group_name, group in (("supported", supported), ("candidate", candidates)):
        for entry in group:
            if not isinstance(entry, dict):
                errors.append(f"compatibility matrix: {group_name} CLI entry must be an object")
                continue
            version = entry.get("version")
            commit = entry.get("commit")
            if not isinstance(version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?", version):
                errors.append(f"compatibility matrix: {group_name} CLI has invalid semantic version")
            if not isinstance(commit, str) or not SHA40.fullmatch(commit):
                errors.append(f"compatibility matrix: {group_name} CLI has non-immutable commit")
            identity = cli_identity(entry)
            if identity in identities:
                errors.append(f"compatibility matrix: duplicate CLI identity {identity}")
            identities.add(identity)
            all_cli_entries.append(entry)

    target_ids: set[str] = set()
    target_kinds: set[str] = set()
    for target in targets:
        if not isinstance(target, dict):
            errors.append("compatibility matrix: target must be an object")
            continue
        target_id = target.get("id")
        kind = target.get("kind")
        contract = target.get("contract")
        if not isinstance(target_id, str) or not target_id:
            errors.append("compatibility matrix: target id must be non-empty")
            continue
        if target_id in target_ids:
            errors.append(f"compatibility matrix: duplicate target id {target_id}")
        target_ids.add(target_id)
        if isinstance(kind, str):
            target_kinds.add(kind)
        if not isinstance(contract, str) or not contract:
            errors.append(f"compatibility matrix: target {target_id} lacks contract identity")
    missing_kinds = sorted(REQUIRED_TARGET_KINDS - target_kinds)
    if missing_kinds:
        errors.append(f"compatibility matrix: missing representative target kinds {missing_kinds}")

    indexed: dict[tuple[str, str], dict[str, Any]] = {}
    for result in results:
        if not isinstance(result, dict):
            errors.append("compatibility matrix: result must be an object")
            continue
        cli = result.get("cli")
        target = result.get("target")
        state = result.get("state")
        if not isinstance(cli, str) or cli not in identities:
            errors.append(f"compatibility matrix: result references unknown CLI {cli!r}")
            continue
        if not isinstance(target, str) or target not in target_ids:
            errors.append(f"compatibility matrix: result references unknown target {target!r}")
            continue
        key = (cli, target)
        if key in indexed:
            errors.append(f"compatibility matrix: duplicate result for {cli} / {target}")
        indexed[key] = result
        if state not in RESULT_STATES:
            errors.append(f"compatibility matrix: invalid state {state!r} for {cli} / {target}")
        if state != "passed" and not result.get("reason"):
            errors.append(f"compatibility matrix: non-passing result lacks reason for {cli} / {target}")

    for entry in all_cli_entries:
        identity = cli_identity(entry)
        for target_id in target_ids:
            if (identity, target_id) not in indexed:
                errors.append(f"compatibility matrix: missing result for {identity} / {target_id}")

    if ready:
        for entry in supported:
            identity = cli_identity(entry)
            for target_id in target_ids:
                result = indexed.get((identity, target_id))
                if not result or result.get("state") != "passed":
                    errors.append(
                        f"compatibility matrix: supported release {identity} is not passed for {target_id}"
                    )
    return errors


def verify_scaffold_contract(document: dict[str, Any], *, release_ready: bool) -> list[str]:
    errors: list[str] = []
    if document.get("schema") != "ores.stack.scaffold-transaction/v1":
        errors.append("scaffold transaction: unsupported schema")
    status = document.get("implementationStatus")
    if status not in {"contract-only", "verified"}:
        errors.append("scaffold transaction: implementationStatus must be contract-only or verified")
    cases = document.get("requiredCases")
    if not isinstance(cases, list):
        return errors + ["scaffold transaction: requiredCases must be an array"]
    ids = {
        case.get("id")
        for case in cases
        if isinstance(case, dict) and isinstance(case.get("id"), str)
    }
    missing = sorted(REQUIRED_SCAFFOLD_CASES - ids)
    if missing:
        errors.append(f"scaffold transaction: missing required cases {missing}")
    for case in cases:
        if not isinstance(case, dict):
            errors.append("scaffold transaction: case must be an object")
            continue
        if not isinstance(case.get("invariant"), str) or not case.get("invariant"):
            errors.append(f"scaffold transaction: case {case.get('id')!r} lacks invariant")
    if release_ready and status != "verified":
        errors.append("scaffold transaction: release authority cannot be ready before transactional scaffolding is verified")
    return errors


def verify_generator_ownership(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if document.get("schema") != "ores.stack.generator-ownership/v1":
        errors.append("generator ownership: unsupported schema")
    if document.get("status") != "required":
        errors.append("generator ownership: status must be required")
    rules = document.get("rules")
    if not isinstance(rules, list):
        return errors + ["generator ownership: rules must be an array"]
    ids = {
        rule.get("id")
        for rule in rules
        if isinstance(rule, dict) and isinstance(rule.get("id"), str)
    }
    missing = sorted(REQUIRED_OWNERSHIP_RULES - ids)
    if missing:
        errors.append(f"generator ownership: missing rules {missing}")
    for rule in rules:
        if not isinstance(rule, dict):
            errors.append("generator ownership: rule must be an object")
            continue
        if not isinstance(rule.get("requirement"), str) or not rule.get("requirement"):
            errors.append(f"generator ownership: rule {rule.get('id')!r} lacks requirement")
    return errors


def verify_cache_integrity(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if document.get("schema") != "ores.stack.cache-integrity/v1":
        errors.append("cache integrity: unsupported schema")
    if document.get("status") != "required":
        errors.append("cache integrity: status must be required")

    dimensions = document.get("requiredKeyDimensions")
    if not isinstance(dimensions, list):
        errors.append("cache integrity: requiredKeyDimensions must be an array")
        dimensions = []
    missing_dimensions = sorted(REQUIRED_CACHE_KEY_DIMENSIONS - set(dimensions))
    if missing_dimensions:
        errors.append(f"cache integrity: incomplete key dimensions {missing_dimensions}")

    hit_requirements = document.get("cacheHitRequirements")
    if not isinstance(hit_requirements, list):
        errors.append("cache integrity: cacheHitRequirements must be an array")
        hit_requirements = []
    missing_hit = sorted(REQUIRED_CACHE_HIT_REQUIREMENTS - set(hit_requirements))
    if missing_hit:
        errors.append(f"cache integrity: missing cache-hit requirements {missing_hit}")

    poison = document.get("poisonHandling")
    expected_poison = {
        "identity_mismatch": "miss",
        "binary_digest_mismatch": "miss",
        "malformed_receipt": "reject",
        "receipt_symlink": "reject",
        "binary_symlink": "reject",
        "missing_dependency_input": "rebuild",
    }
    if not isinstance(poison, dict):
        errors.append("cache integrity: poisonHandling must be an object")
    else:
        for key, expected in expected_poison.items():
            if poison.get(key) != expected:
                errors.append(
                    f"cache integrity: poison handling for {key} must be {expected!r}"
                )
    return errors


def verify_plugin_execution(document: dict[str, Any], *, release_ready: bool) -> list[str]:
    errors: list[str] = []
    if document.get("schema") != "ores.stack.plugin-execution/v1":
        errors.append("plugin execution: unsupported schema")
    status = document.get("status")
    if status not in {"contract-only", "verified"}:
        errors.append("plugin execution: status must be contract-only or verified")
    requirements = document.get("requirements")
    if not isinstance(requirements, list):
        return errors + ["plugin execution: requirements must be an array"]
    missing = sorted(REQUIRED_PLUGIN_REQUIREMENTS - set(requirements))
    if missing:
        errors.append(f"plugin execution: missing requirements {missing}")
    if release_ready and status != "verified":
        errors.append(
            "plugin execution: release authority cannot be ready before constrained plugin execution is verified"
        )
    return errors


def diagnose(snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    """Return deterministic read-only findings; never mutate the supplied snapshot."""
    findings: list[dict[str, Any]] = []

    if "repository" in snapshot:
        repository = snapshot.get("repository")
        if repository != CANONICAL_REPOSITORY:
            code = "fleet.cli.legacy-repository" if repository == "https://github.com/ORESoftware/ores-stack" else "fleet.cli.repository-mismatch"
            findings.append({"code": code, "observed": repository, "expected": CANONICAL_REPOSITORY})

    if "commit" in snapshot:
        commit = snapshot.get("commit")
        if not isinstance(commit, str) or not SHA40.fullmatch(commit):
            findings.append({"code": "fleet.cli.mutable-or-missing-commit", "observed": commit})

    if "sha256" in snapshot:
        checksum = snapshot.get("sha256")
        if not isinstance(checksum, str) or not SHA256.fullmatch(checksum):
            findings.append({"code": "fleet.cli.missing-or-invalid-checksum", "observed": checksum})

    resolved = snapshot.get("resolvedBinary")
    pinned = snapshot.get("pinnedBinary")
    if resolved and pinned and Path(str(resolved)).resolve(strict=False) != Path(str(pinned)).resolve(strict=False):
        findings.append({"code": "fleet.cli.path-shadowed", "resolved": str(resolved), "pinned": str(pinned)})

    for field, code in (
        ("missingAdapters", "fleet.cli.missing-adapter"),
        ("staleGenerators", "fleet.cli.stale-generator"),
        ("unresolvedDependencies", "fleet.cli.unresolved-dependency"),
        ("incompatibleConfiguration", "fleet.cli.incompatible-configuration"),
    ):
        values = snapshot.get(field, [])
        if not isinstance(values, list):
            findings.append({"code": "fleet.cli.invalid-diagnostic-input", "field": field})
            continue
        for value in values:
            findings.append({"code": code, "detail": value})

    return findings


def live_snapshot(expected_cli: Path | None) -> dict[str, Any]:
    resolved = shutil.which("ores-stack")
    return {
        "resolvedBinary": resolved,
        "pinnedBinary": str(expected_cli) if expected_cli else None,
        "missingAdapters": [],
        "staleGenerators": [],
        "unresolvedDependencies": [],
        "incompatibleConfiguration": [],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--diagnose", type=Path, help="read a diagnostic snapshot JSON")
    parser.add_argument("--live-path", action="store_true", help="diagnose the currently resolved ores-stack PATH entry")
    parser.add_argument("--expected-cli", type=Path, help="expected pinned ores-stack executable for --live-path")
    parser.add_argument("--strict", action="store_true", help="return non-zero when diagnostics emit findings")
    args = parser.parse_args()

    matrix = load_json(MATRIX_PATH)
    scaffold = load_json(SCAFFOLD_PATH)
    ownership = load_json(OWNERSHIP_PATH)
    cache = load_json(CACHE_PATH)
    plugin = load_json(PLUGIN_PATH)
    release_ready = bool(matrix.get("releaseAuthorityReady"))
    errors = verify_matrix(matrix)
    errors.extend(
        verify_scaffold_contract(
            scaffold,
            release_ready=release_ready,
        )
    )
    errors.extend(verify_generator_ownership(ownership))
    errors.extend(verify_cache_integrity(cache))
    errors.extend(verify_plugin_execution(plugin, release_ready=release_ready))
    if errors:
        for error in errors:
            print(f"ERROR {error}")
        return 1

    if args.diagnose and args.live_path:
        print("ERROR choose either --diagnose or --live-path")
        return 2

    snapshot: dict[str, Any] | None = None
    if args.diagnose:
        snapshot = load_json(args.diagnose)
    elif args.live_path:
        snapshot = live_snapshot(args.expected_cli)

    if snapshot is not None:
        findings = diagnose(snapshot)
        print(json.dumps({"findings": findings}, indent=2, sort_keys=True))
        if args.strict and findings:
            return 1
        return 0

    print(
        "ORES Stack CLI hardening contracts OK: "
        f"{len(matrix.get('supportedReleases', []))} supported release(s), "
        f"{len(matrix.get('migrationCandidates', []))} migration candidate(s), "
        f"scaffolding={scaffold.get('implementationStatus')}, "
        f"plugins={plugin.get('status')}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
