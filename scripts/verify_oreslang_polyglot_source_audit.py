#!/usr/bin/env python3
"""Validate the immutable *source-path* audit snapshot, never runtime readiness.

This file is intentionally offline: private cross-organization repos cannot be
checked out with the source repo GITHUB_TOKEN.  CI verifies the integrity of
the dated snapshot, NOT the current remote repo state.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AUDIT = ROOT / "shared/oreslang-polyglot-source-audit.json"
SCHEMA = "ores.comparisons.oreslang-polyglot-source-audit/v1"
FIELDS = frozenset({
    "schema", "as_of", "scope", "minimum_organizations", "certification",
    "limitations", "organizations",
})
ROW_FIELDS = frozenset({
    "organization", "client_repository", "interface_repository", "branch",
    "client_oreslang_sources_observed", "interface_oreslang_sources_observed",
    "execution_certified",
})
ORG = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]*")


def validate(audit: object) -> list[str]:
    if not isinstance(audit, dict):
        return ["audit must be an object"]
    errors: list[str] = []
    if set(audit) != FIELDS:
        errors.append("unexpected/missing audit fields; do not introduce admission claims")
    if audit.get("schema") != SCHEMA:
        errors.append("schema changed without review")
    if audit.get("as_of") != "2026-10-10":
        errors.append("snapshot date changed: perform a new live audit instead")
    if audit.get("scope") != "main-branch-recursive-tree-path-inspection":
        errors.append("snapshot scope must not claim executable proof")
    if audit.get("certification") != "none":
        errors.append("static source-path audit is not runtime certification")
    if not isinstance(audit.get("limitations"), str) or not audit["limitations"].strip():
        errors.append("audit limitations must be explicit")
    if audit.get("minimum_organizations") != 15:
        errors.append("minimum audited organizations must remain 15")
    rows = audit.get("organizations")
    if not isinstance(rows, list):
        return errors + ["organizations must be an array"]
    if len(rows) < 15:
        errors.append("fewer than 15 organizations audited")
    seen_orgs: set[str] = set()
    seen_repos: set[str] = set()
    for index, row in enumerate(rows):
        if not isinstance(row, dict) or set(row) != ROW_FIELDS:
            errors.append(f"row {index}: unexpected/missing fields")
            continue
        org = row["organization"]
        if not isinstance(org, str) or not ORG.fullmatch(org) or org in seen_orgs:
            errors.append(f"row {index}: malformed/duplicate organization")
            continue
        seen_orgs.add(org)
        for role, field, suffix in (
            ("client", "client_repository", "-clients"),
            ("interface", "interface_repository", "-interfaces"),
        ):
            repo = row[field]
            if not isinstance(repo, str) or not repo.startswith(org + "/") or repo.count("/") != 1 or not repo.endswith(suffix):
                errors.append(f"{org}: invalid {role} repository identity")
                continue
            if repo in seen_repos:
                errors.append(f"{org}: duplicate repository across organizations")
            seen_repos.add(repo)
        if row["branch"] != "main":
            errors.append(f"{org}: this snapshot only audited default main")
        for field in ("client_oreslang_sources_observed",
                      "interface_oreslang_sources_observed", "execution_certified"):
            if row[field] is not False:
                errors.append(f"{org}: {field} cannot be promoted by a source-path snapshot")
    return errors


def main() -> int:
    errors = validate(json.loads(AUDIT.read_text(encoding="utf-8")))
    if errors:
        for error in errors:
            print("oreslang source audit:", error)
        return 1
    print("oreslang source audit: 18-organization baseline validated; NO runtime certified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
