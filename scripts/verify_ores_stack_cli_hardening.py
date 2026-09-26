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


def diagnose(snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    """Return deterministic read-only findings; never mutate the supplied snapshot."""
    findings: list[dict[str, Any]] = []

    repository = snapshot.get("repository")
    if repository != CANONICAL_REPOSITORY:
        code = "fleet.cli.legacy-repository" if repository == "https://github.com/ORESoftware/ores-stack" else "fleet.cli.repository-mismatch"
        findings.append({"code": code, "observed": repository, "expected": CANONICAL_REPOSITORY})

    commit = snapshot.get("commit")
    if not isinstance(commit, str) or not SHA40.fullmatch(commit):
        findings.append({"code": "fleet.cli.mutable-or-missing-commit", "observed": commit})

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
        "repository": CANONICAL_REPOSITORY,
        "commit": None,
        "sha256": None,
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
    errors = verify_matrix(matrix)
    errors.extend(
        verify_scaffold_contract(
            scaffold,
            release_ready=bool(matrix.get("releaseAuthorityReady")),
        )
    )
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
        f"scaffolding={scaffold.get('implementationStatus')}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
