#!/usr/bin/env python3
"""Emit a truthful, SHA-bound GitHub CI evidence-scope receipt.

A workflow's success is not evidence that private or submodule-dependent
checks ran. This receipt is metadata only, never a substitute for executable
proof. No token values or secret-derived hashes are recorded.
"""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

JOB_RESULTS = {"success", "failure", "cancelled", "skipped"}
SHA40 = re.compile(r"[0-9a-f]{40}\Z")
SCHEMA = "ores.comparisons.ci-evidence-scope/v1"


def status(job_result: str, authorized: bool) -> str:
    if job_result == "failure":
        return "failed"
    if job_result in {"cancelled", "skipped"}:
        return "not_run"
    return "passed" if authorized else "blocked"


def make_receipt(
    *,
    revision: str,
    repository: str,
    static_result: str,
    private_result: str,
    submodules_present: bool,
    private_credential: bool,
    source_credential: bool,
) -> dict:
    if not SHA40.fullmatch(revision):
        raise ValueError("revision must be an exact lower-case 40-hex Git SHA")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or any(
        part in {".", ".."} for part in repository.split("/")
    ):
        raise ValueError("repository must have safe owner/name shape")
    if static_result not in JOB_RESULTS or private_result not in JOB_RESULTS:
        raise ValueError("unknown GitHub job result")
    if type(submodules_present) is not bool:
        raise ValueError("submodules_present must be bool")
    if type(private_credential) is not bool or type(source_credential) is not bool:
        raise ValueError("credential presence must be bool")

    public_scaffold = status(static_result, authorized=True)
    # verify.yml uses 'hashFiles(.gitmodules) == empty' to gate
    # content-dependent checks; they did NOT run in ordinary clones.
    project_content = status(static_result, authorized=not submodules_present)
    private_authorities = status(private_result, authorized=private_credential)
    source_census = status(private_result, authorized=source_credential)
    scopes = {
        "public_static_structure": public_scaffold,
        "public_project_content": project_content,
        "private_contract_and_smoke": private_authorities,
        "native_source_remote_census": source_census,
        # These are separate workflows; verify.yml cannot assert them.
        "runtime_proof_set_18": "not_run",
    }
    complete = all(value == "passed" for value in scopes.values())
    return {
        "schema": SCHEMA,
        "repository": repository,
        "revision": revision,
        "coverage_status": "complete" if complete else "incomplete",
        "full_runtime_proof": complete,
        "job_results": {"static_contracts": static_result, "private_authorities": private_result},
        "scopes": scopes,
        "unverified_scopes": sorted(key for key, value in scopes.items() if value != "passed"),
        "notes": [
            "This workflow does not execute or attest runtime-proof-set-18.",
            "A skipped or credential-blocked CI job is never represented as private proof.",
            "A checked-in .gitmodules means the public content-level gates are skipped in verify.yml.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--static-result", choices=sorted(JOB_RESULTS), required=True)
    parser.add_argument("--private-result", choices=sorted(JOB_RESULTS), required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--private-auth", choices=("yes", "no", "unknown"), default="unknown")
    parser.add_argument("--source-auth", choices=("yes", "no", "unknown"), default="unknown")
    parser.add_argument("--output", type=Path, default=Path("artifacts/ci-evidence-scope.json"))
    parser.add_argument("--require-complete", action="store_true", help="fail unless every evidence domain was exercised")
    args = parser.parse_args()

    receipt = make_receipt(
        revision=args.revision,
        repository=args.repository,
        static_result=args.static_result,
        private_result=args.private_result,
        submodules_present=Path(".gitmodules").is_file(),
        private_credential=args.private_auth == "yes",
        source_credential=args.source_auth == "yes",
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"CI evidence receipt for {args.revision}: {receipt['coverage_status']}")
    for key, value in receipt["scopes"].items():
        print(f"  {key}: {value}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY", "")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(f"### CI verification scope: {receipt['coverage_status']}\n\n")
            stream.write("| Evidence domain | State |\n| --- | --- |\n")
            for key, value in receipt["scopes"].items():
                stream.write(f"| \`{key}\` | \`{value}\` |\n")
            stream.write("\nThis is a scope report, not runtime proof. See the uploaded JSON artifact.\n")
    if args.require_complete and not receipt["full_runtime_proof"]:
        print("ERROR: full verification is not established; refusing completion")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
