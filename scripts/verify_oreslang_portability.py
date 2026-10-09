#!/usr/bin/env python3
"""Fail-closed Oreslang source fixture portability ledger for every comparison stack."""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHA_RE = re.compile(r"[0-9a-f]{40}\Z")
NAME_RE = re.compile(r"[A-Za-z0-9_.-]+\Z")
PORT_RE = re.compile(r"stacks/[^/]+/projects/oreslang-portability/repos/[^/]+\Z")


def git(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True)


def verify() -> list[str]:
    m = json.loads((ROOT / "shared/oreslang-portability.json").read_text())
    c = json.loads((ROOT / "shared/stack-catalog.json").read_text())
    errors: list[str] = []
    def bad(s: str) -> None:
        errors.append(s)

    if m.get("schema") != "ores.comparisons.oreslang-portability/v1":
        bad("wrong portability schema")
    if m.get("framework_stack") != "oreslang-stack" or m.get("source_language") != "oreslang":
        bad("wrong Oreslang stack or source identity")
    if m.get("framework_cli_repository") != "ores-truffle-oreslang/oreslang-stack-cli":
        bad("wrong Oreslang framework CLI")
    if m.get("fixture_organization") != "ores-dummy-org-oreslang-stack" or m.get("transport") != "git-submodule":
        bad("wrong fixture organization or transport")
    if m.get("expected_fixture_repository_count") != 2:
        bad("fixture organization expected to provide two real repositories")
    if m.get("deployment_targets") != [{"name": "oreslang-stack", "status": "proposed"}, {"name": "oreslang-faas", "status": "proposed"}]:
        bad("deployment labels cannot imply executable FaaS proof")
    stacks = {e["id"]: e for e in c["stacks"]}
    ores = stacks.get("oreslang-stack", {})
    rust = stacks.get("ores-stack", {})
    if ores.get("status") != "registered" or ores.get("topology_status") != "pending" or ores.get("benchmark_executable") is not None or ores.get("runtime_fixture_orgs") != []:
        bad("Oreslang must remain registered, pending and not executable until proven")
    if rust.get("github_owner") != "ores-stack" or "Rust" not in rust.get("execution_model", ""):
        bad("Rust ORES Stack identity changed")
    allowed = {
        "oreslang-stack": ("planned", "oreslang-native"),
        "scintilla-run": ("planned", "oreslang-runtime-adapter"),
        "wasm-xprs": ("future-backend", "wasm"),
        "iso-lattes": ("future-backend", "js-or-wasm"),
        "lunatic-lorry": ("future-backend", "wasm"),
    }
    deployments = m.get("deployment_matrix", [])
    if not isinstance(deployments, list) or len(deployments) != len(stacks):
        bad("deployment matrix must include every registered comparison stack")
        deployments = []
    seen_deployment_stacks = set()
    for entry in deployments:
        if not isinstance(entry, dict) or not isinstance(entry.get("stack"), str):
            bad("invalid deployment matrix record")
            continue
        name = entry["stack"]
        if name in seen_deployment_stacks or name not in stacks:
            bad(f"unknown or duplicate deployment target {name!r}")
        seen_deployment_stacks.add(name)
        expected_status, expected_artifact = allowed.get(name, ("unsupported", None))
        if entry.get("status") != expected_status or entry.get("artifact") != expected_artifact:
            bad(f"{name}: illegal deployment capability or backend {entry!r}")
        if not isinstance(entry.get("reason"), str) or not entry["reason"].strip():
            bad(f"{name}: missing deployment rationale")
    if seen_deployment_stacks != set(stacks):
        bad("deployment matrix must exactly enumerate catalog stacks")
    repositories = m.get("fixture_repositories")
    if not isinstance(repositories, list) or len(repositories) > 2:
        bad("invalid repository inventory")
        repositories = []
    names = []
    for r in repositories:
        if not isinstance(r, dict) or not isinstance(r.get("name"), str) or not NAME_RE.fullmatch(r["name"]) or not isinstance(r.get("branch"), str) or not NAME_RE.fullmatch(r["branch"]):
            bad(f"invalid repository identity: {r!r}")
        else:
            names.append(r["name"])
    if len(names) != len(set(names)):
        bad("duplicate source repository names")
    by_name = {r["name"]: r for r in repositories if isinstance(r, dict) and r.get("name") in names}
    targets = m.get("targets")
    if not isinstance(targets, list):
        bad("targets must be an array")
        targets = []
    target_names = [t.get("stack") for t in targets if isinstance(t, dict)]
    if len(target_names) != len(targets) or len(set(target_names)) != len(target_names) or set(target_names) != set(stacks):
        bad("target stacks must equal catalog stacks (including Rust and Oreslang) exactly")
    indexed = git("ls-files", "--stage")
    if indexed.returncode:
        bad("cannot read Git index")
    known_gitlinks = {}
    for line in indexed.stdout.splitlines():
        left, path = line.split("\t", 1)
        mode, sha, _stage = left.split()
        if mode == "160000":
            known_gitlinks[path] = sha

    mod = git("config", "-f", str(ROOT / ".gitmodules"), "--get-regexp", r"^submodule\..*\.(path|url|branch)$")
    if mod.returncode not in (0, 1):
        bad("invalid .gitmodules")
    groups = {}
    for line in mod.stdout.splitlines():
        key, value = line.split(None, 1)
        match = re.fullmatch(r"submodule\.(.+)\.(path|url|branch)", key)
        if match:
            group, field = match.groups()
            groups.setdefault(group, {})[field] = value
    modules = {}
    for fields in groups.values():
        if fields.get("path"):
            if fields["path"] in modules:
                bad(f"duplicate .gitmodules path {fields['path']}")
            modules[fields["path"]] = fields

    declared = set()
    for t in targets:
        if not isinstance(t, dict):
            bad("target must be an object")
            continue
        stack, status, pins = t.get("stack"), t.get("status"), t.get("pins")
        if status not in ("pending", "partial", "complete") or not isinstance(pins, list):
            bad(f"{stack}: invalid status/pins")
            continue
        if status == "pending" and pins:
            bad(f"{stack}: pending cannot contain gitlink pins")
        if status != "pending" and len(names) != 2:
            bad(f"{stack}: cannot pin without full two-repo inventory")
        if status == "complete" and len(pins) != 2:
            bad(f"{stack}: complete requires both fixture repositories")
        if status == "partial" and len(pins) != 1:
            bad(f"{stack}: partial requires one fixture repository")
        seen = set()
        for p in pins:
            if not isinstance(p, dict):
                bad(f"{stack}: invalid pin object")
                continue
            name, path, sha = p.get("repository"), p.get("path"), p.get("sha")
            if name not in by_name or name in seen:
                bad(f"{stack}: unknown/duplicate source repository {name!r}")
                continue
            seen.add(name)
            expected = f"stacks/{stack}/projects/oreslang-portability/repos/{name}"
            if path != expected or not isinstance(sha, str) or not SHA_RE.fullmatch(sha):
                bad(f"{stack}: malformed pin for {name}")
                continue
            declared.add(path)
            if known_gitlinks.get(path) != sha:
                bad(f"{path}: Git index does not pin declared commit")
            cfg = modules.get(path, {})
            if cfg.get("url") != f"https://github.com/ores-dummy-org-oreslang-stack/{name}.git" or cfg.get("branch") != by_name[name]["branch"]:
                bad(f"{path}: .gitmodules URL/branch mismatch")
        if status == "complete" and seen != set(names):
            bad(f"{stack}: missing repo despite complete status")
    actual = {p for p in known_gitlinks if PORT_RE.fullmatch(p)}
    if actual != declared:
        bad(f"unadmitted or missing Gitlinks: extra={sorted(actual-declared)}, missing={sorted(declared-actual)}")
    return errors


if __name__ == "__main__":
    failures = verify()
    if failures:
        print("Oreslang portability FAILED")
        for failure in failures:
            print(" -", failure)
        raise SystemExit(1)
    print("Oreslang portability OK: catalog targets enumerated and Gitlinks proven or explicitly pending")
