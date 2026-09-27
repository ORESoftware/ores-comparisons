#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def revision(root: Path) -> str:
    value = subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"],
        text=True,
        stderr=subprocess.DEVNULL,
    ).strip()
    if not SHA40.fullmatch(value):
        raise RuntimeError(f"checkout revision is not an exact commit: {value!r}")
    return value


def expected_projects(root: Path) -> list[tuple[str, str]]:
    matrix = json.loads((root / "shared" / "project-matrix.json").read_text())
    projects = matrix.get("projects")
    if not isinstance(projects, list) or not projects:
        raise RuntimeError("project matrix has no projects")
    pairs: list[tuple[str, str]] = []
    for item in projects:
        stack = item.get("stack") if isinstance(item, dict) else None
        scenario = item.get("scenario") if isinstance(item, dict) else None
        if not isinstance(stack, str) or not isinstance(scenario, str):
            raise RuntimeError("project matrix contains an invalid stack/scenario entry")
        pairs.append((stack, scenario))
    if len(set(pairs)) != len(pairs):
        raise RuntimeError("project matrix contains duplicate stack/scenario entries")
    return sorted(pairs)


def expected_gitlinks(root: Path, stack: str, scenario: str) -> dict[str, str]:
    ledger = json.loads((root / "shared" / "dummy-org-gitlinks.json").read_text())
    links = {
        item["path"]: item["commit"]
        for item in ledger.get("entries", [])
        if isinstance(item, dict)
        and item.get("stack") == stack
        and item.get("scenario") == scenario
    }
    if not links:
        raise RuntimeError(f"gitlink ledger has no entries for {stack}/{scenario}")
    for path, commit in links.items():
        if not isinstance(path, str) or not isinstance(commit, str) or not SHA40.fullmatch(commit):
            raise RuntimeError(f"gitlink ledger has invalid entry for {stack}/{scenario}")
    return dict(sorted(links.items()))


def expected_ores_compose_commit(root: Path) -> str:
    lock = json.loads((root / "tools" / "toolchain.lock.json").read_text())
    value = lock.get("tools", {}).get("ores-compose", {}).get("commit")
    if not isinstance(value, str) or not SHA40.fullmatch(value):
        raise RuntimeError("toolchain lock has no exact ores-compose commit")
    return value


def expected_manifest(stack: str, scenario: str) -> str:
    return (
        f"stacks/{stack}/projects/{scenario}/repos/.github/.ores-compose.yaml"
    )


def require_sha256(value: object, label: str) -> str:
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        raise RuntimeError(f"{label} must be a lowercase SHA-256 digest")
    return value


def validate_receipt(
    *,
    root: Path,
    receipt: dict[str, object],
    stack: str,
    scenario: str,
    expected_revision: str,
    project_matrix_sha256: str,
    gitlink_ledger_sha256: str,
    toolchain_lock_sha256: str,
    compose_commit: str,
) -> None:
    label = f"{stack}/{scenario}"
    if receipt.get("schema") != "ores.comparisons.runtime-proof/v2":
        raise RuntimeError(f"{label}: unsupported runtime proof schema")
    if receipt.get("stack") != stack or receipt.get("scenario") != scenario:
        raise RuntimeError(f"{label}: receipt identity does not match artifact filename")
    if receipt.get("revision") != expected_revision:
        raise RuntimeError(f"{label}: receipt revision does not match aggregate checkout")
    if receipt.get("status") != "passed":
        raise RuntimeError(f"{label}: runtime status is not passed")
    if receipt.get("composeReady") is not True:
        raise RuntimeError(f"{label}: composeReady is not true")
    if receipt.get("returnCode") != 0:
        raise RuntimeError(f"{label}: runtime did not exit cleanly")

    ready = receipt.get("readyEvent")
    if not isinstance(ready, dict) or ready.get("event") != "compose_ready":
        raise RuntimeError(f"{label}: machine-readable compose_ready event is missing")

    manifest = expected_manifest(stack, scenario)
    if receipt.get("manifest") != manifest:
        raise RuntimeError(f"{label}: manifest path does not match governed project")
    if receipt.get("command") != ["ores-compose", "up", manifest]:
        raise RuntimeError(f"{label}: runtime command is not canonical ores-compose up")

    if receipt.get("projectMatrixSha256") != project_matrix_sha256:
        raise RuntimeError(f"{label}: project matrix digest drift")
    if receipt.get("gitlinkLedgerSha256") != gitlink_ledger_sha256:
        raise RuntimeError(f"{label}: gitlink ledger digest drift")
    if receipt.get("toolchainLockSha256") != toolchain_lock_sha256:
        raise RuntimeError(f"{label}: toolchain lock digest drift")
    if receipt.get("oresComposeCommit") != compose_commit:
        raise RuntimeError(f"{label}: ores-compose source commit drift")

    require_sha256(receipt.get("manifestSha256"), f"{label}: manifestSha256")
    require_sha256(receipt.get("composeBinarySha256"), f"{label}: composeBinarySha256")

    governed_gitlinks = expected_gitlinks(root, stack, scenario)
    if receipt.get("gitlinks") != governed_gitlinks:
        raise RuntimeError(f"{label}: exact gitlinks do not match governed ledger")


def verify_receipts(
    *,
    root: Path,
    directory: Path,
    expected_revision: str | None = None,
) -> dict[str, object]:
    pairs = expected_projects(root)
    revision_value = expected_revision or revision(root)
    if not SHA40.fullmatch(revision_value):
        raise RuntimeError(f"expected revision is not an exact commit: {revision_value!r}")

    expected_names = {f"{stack}-{scenario}.json" for stack, scenario in pairs}
    actual_names = {path.name for path in directory.glob("*.json")}
    missing = sorted(expected_names - actual_names)
    extra = sorted(actual_names - expected_names)
    if missing or extra:
        raise RuntimeError(
            f"runtime receipt set mismatch: missing={missing}, extra={extra}"
        )

    project_matrix_sha256 = sha256_file(root / "shared" / "project-matrix.json")
    gitlink_ledger_sha256 = sha256_file(root / "shared" / "dummy-org-gitlinks.json")
    toolchain_lock_sha256 = sha256_file(root / "tools" / "toolchain.lock.json")
    compose_commit = expected_ores_compose_commit(root)

    validated: list[dict[str, object]] = []
    for stack, scenario in pairs:
        path = directory / f"{stack}-{scenario}.json"
        try:
            receipt = json.loads(path.read_text())
        except (OSError, json.JSONDecodeError) as error:
            raise RuntimeError(f"{path.name}: invalid receipt JSON: {error}") from error
        if not isinstance(receipt, dict):
            raise RuntimeError(f"{path.name}: receipt must be a JSON object")
        validate_receipt(
            root=root,
            receipt=receipt,
            stack=stack,
            scenario=scenario,
            expected_revision=revision_value,
            project_matrix_sha256=project_matrix_sha256,
            gitlink_ledger_sha256=gitlink_ledger_sha256,
            toolchain_lock_sha256=toolchain_lock_sha256,
            compose_commit=compose_commit,
        )
        validated.append(
            {
                "stack": stack,
                "scenario": scenario,
                "receipt": path.name,
                "receiptSha256": sha256_file(path),
                "manifestSha256": receipt["manifestSha256"],
                "composeBinarySha256": receipt["composeBinarySha256"],
            }
        )

    canonical_receipts = json.dumps(
        validated, sort_keys=True, separators=(",", ":")
    ).encode()
    proof_set_sha256 = hashlib.sha256(canonical_receipts).hexdigest()
    return {
        "schema": "ores.comparisons.runtime-proof-set/v1",
        "status": "passed",
        "count": len(validated),
        "revision": revision_value,
        "projectMatrixSha256": project_matrix_sha256,
        "gitlinkLedgerSha256": gitlink_ledger_sha256,
        "toolchainLockSha256": toolchain_lock_sha256,
        "oresComposeCommit": compose_commit,
        "proofSetSha256": proof_set_sha256,
        "receipts": validated,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify the complete exact-source ores-comparisons runtime proof set."
    )
    parser.add_argument(
        "directory",
        nargs="?",
        default=str(ROOT / "artifacts" / "runtime-18"),
    )
    parser.add_argument("--summary")
    args = parser.parse_args()
    try:
        summary = verify_receipts(
            root=ROOT,
            directory=Path(args.directory).resolve(),
        )
    except Exception as error:
        print(f"runtime proof set FAILED: {error}")
        return 1

    encoded = json.dumps(summary, indent=2, sort_keys=True) + "\n"
    if args.summary:
        path = Path(args.summary).resolve()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(encoded)
    print(encoded, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
