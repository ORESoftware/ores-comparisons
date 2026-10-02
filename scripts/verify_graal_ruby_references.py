#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "shared/graal-ruby-references.json"

config = json.loads(CONFIG.read_text())
errors: list[str] = []

if config.get("schema") != "ores.comparisons.graal-ruby-references/v1":
    errors.append(f"unexpected Graal Ruby reference schema: {config.get('schema')!r}")

index: dict[str, tuple[str, str]] = {}
for line in subprocess.run(
    ["git", "ls-files", "--stage"],
    cwd=ROOT,
    check=True,
    text=True,
    stdout=subprocess.PIPE,
).stdout.splitlines():
    metadata, path = line.split("\t", 1)
    mode, sha, _stage = metadata.split()
    index[path] = (mode, sha)

modules: dict[str, dict[str, str]] = {}
proc = subprocess.run(
    ["git", "config", "-f", str(ROOT / ".gitmodules"), "--get-regexp", r"^submodule\..*\.(path|url|branch)$"],
    cwd=ROOT,
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
)
if proc.returncode not in (0, 1):
    raise SystemExit(proc.stderr.strip() or "failed to parse .gitmodules")
pattern = re.compile(r"^submodule\.(.+)\.(path|url|branch)$")
for line in proc.stdout.splitlines():
    key, value = line.split(None, 1)
    match = pattern.fullmatch(key)
    if match:
        name, field = match.groups()
        modules.setdefault(name, {})[field] = value

modules_by_path = {
    values["path"]: values
    for values in modules.values()
    if "path" in values
}

seen: set[str] = set()
for ref in config.get("references", []):
    path = ref.get("path")
    repository = ref.get("repository")
    branch = ref.get("branch")
    if not all(isinstance(value, str) and value for value in (path, repository, branch)):
        errors.append(f"invalid Graal Ruby reference: {ref!r}")
        continue
    if path in seen:
        errors.append(f"duplicate Graal Ruby reference path: {path}")
        continue
    seen.add(path)

    indexed = index.get(path)
    if indexed is None or indexed[0] != "160000":
        errors.append(f"{path}: expected pinned gitlink")

    module = modules_by_path.get(path)
    if module is None:
        errors.append(f"{path}: missing .gitmodules entry")
        continue
    expected_url = f"https://github.com/{repository}.git"
    if module.get("url") != expected_url:
        errors.append(f"{path}: url {module.get('url')!r} != {expected_url!r}")
    if module.get("branch") != branch:
        errors.append(f"{path}: branch {module.get('branch')!r} != {branch!r}")

if errors:
    print("Graal Ruby reference verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print(f"Graal Ruby reference verification OK: {len(seen)} exact gitlinks")
