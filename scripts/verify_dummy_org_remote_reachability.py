#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import tempfile
from collections import defaultdict
from pathlib import Path

from project_matrix import ROOT

LEDGER = ROOT / "shared/dummy-org-gitlinks.json"
EXPECTED_SCHEMA = "ores.comparisons.dummy-org-gitlinks/v1"


def run(args: list[str], cwd: Path, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=cwd,
        check=check,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


data = json.loads(LEDGER.read_text())
if data.get("schema") != EXPECTED_SCHEMA:
    raise SystemExit(f"unsupported ledger schema: {data.get('schema')!r}")

entries = data.get("entries")
if not isinstance(entries, list) or not entries:
    raise SystemExit("gitlink ledger entries must be a non-empty array")

grouped: dict[str, list[dict[str, str]]] = defaultdict(list)
for raw in entries:
    if not isinstance(raw, dict):
        raise SystemExit("gitlink ledger contains a non-object entry")
    repository = raw.get("repository")
    branch = raw.get("branch")
    commit = raw.get("commit")
    path = raw.get("path")
    if not all(isinstance(value, str) and value for value in (repository, branch, commit, path)):
        raise SystemExit(f"invalid ledger entry: {raw!r}")
    grouped[repository].append(raw)

errors: list[str] = []
checked = 0

with tempfile.TemporaryDirectory(prefix="ores-comparisons-remote-audit-") as temp:
    temp_root = Path(temp)
    for repository, repo_entries in sorted(grouped.items()):
        owner, name = repository.split("/", 1)
        checkout = temp_root / f"{owner}__{name.replace('.', '_')}"
        checkout.mkdir(parents=True)
        run(["git", "init", "--quiet"], checkout)
        remote = f"https://github.com/{repository}.git"
        run(["git", "remote", "add", "origin", remote], checkout)

        branches = sorted({entry["branch"] for entry in repo_entries})
        refspecs = [
            f"+refs/heads/{branch}:refs/remotes/origin/{branch}"
            for branch in branches
        ]
        fetched = run(
            ["git", "fetch", "--quiet", "--no-tags", "--filter=blob:none", "origin", *refspecs],
            checkout,
            check=False,
        )
        if fetched.returncode != 0:
            errors.append(f"{repository}: fetch failed: {fetched.stderr.strip()}")
            continue

        for entry in repo_entries:
            commit = entry["commit"]
            branch = entry["branch"]
            path = entry["path"]
            remote_ref = f"refs/remotes/origin/{branch}"
            exists = run(["git", "cat-file", "-e", f"{commit}^{{commit}}"], checkout, check=False)
            if exists.returncode != 0:
                errors.append(f"{path}: ledger commit {commit} was not fetched")
                continue
            ancestor = run(
                ["git", "merge-base", "--is-ancestor", commit, remote_ref],
                checkout,
                check=False,
            )
            if ancestor.returncode == 1:
                tip = run(["git", "rev-parse", remote_ref], checkout).stdout.strip()
                errors.append(
                    f"{path}: {commit} is not reachable from {repository}@{branch} tip {tip}"
                )
                continue
            if ancestor.returncode != 0:
                errors.append(f"{path}: reachability check failed: {ancestor.stderr.strip()}")
                continue
            checked += 1

if checked != len(entries):
    errors.append(f"verified {checked} of {len(entries)} ledger entries")

if errors:
    print("dummy-org remote reachability FAILED")
    for error in errors:
        print(f" - {error}")
    raise SystemExit(1)

print(
    f"dummy-org remote reachability OK: {checked} gitlinks across "
    f"{len(grouped)} repositories remain reachable"
)
