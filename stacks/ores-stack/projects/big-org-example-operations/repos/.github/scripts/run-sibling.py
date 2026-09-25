#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

SHARED = Path(__file__).resolve().parents[1]
ORG = SHARED.parent
TOPOLOGY = json.loads((SHARED / "runtime/topology.json").read_text())


def find_root() -> Path:
    for candidate in [SHARED, *SHARED.parents]:
        if (candidate / "tools/toolchain.lock.json").is_file():
            return candidate
    raise SystemExit("cannot find ores-comparisons repository root")


ROOT = find_root()


def tool(name: str) -> str:
    local = ROOT / ".local/bin" / name
    if local.exists():
        return str(local)
    found = shutil.which(name)
    if found:
        return found
    raise SystemExit(f"missing {name}; run just tools-bootstrap first")


def service(name: str) -> dict:
    for item in TOPOLOGY["services"]:
        if item["name"] == name:
            if item["role"] == "application":
                raise SystemExit("application repo is launched by scripts/dev-server.sh")
            return item
    raise SystemExit(f"unknown or unauthorized sibling service: {name!r}")


def admitted_repo(item: dict) -> Path:
    name = item["repository"]
    if not name or "/" in name or name in {".", ".."}:
        raise SystemExit("invalid repository name in admitted topology")
    candidate = (ORG / name).resolve()
    if candidate.parent != ORG.resolve() or not candidate.is_dir():
        raise SystemExit(f"admitted sibling repository missing: {name}")
    return candidate


def stack_command(mode: str, item: dict) -> tuple[list[str], dict[str, str]]:
    stack = TOPOLOGY["stack"]
    env = os.environ.copy()
    if stack == "beamscale":
        env["BMSCL_COMPILER"] = tool("bmscl-compiler")
        env["BMSCL_SUPERVISOR_ROOT"] = str(ROOT / ".local/runtimes/bmscl-supervisor")
        if mode == "build":
            return [
                tool("bmscl"), "build", ".", "--out-dir", "dist",
                "--policy", "bmscl-policy.toml",
                "--worker-config", ".ores-lambda.toml",
            ], env
        return [tool("bmscl"), "dev", "."], env
    if stack == "scintilla-run":
        env["SCINTILLA_BASE_URL"] = "http://127.0.0.1:8080"
        if mode == "build":
            return [tool("scintilla"), "build", "--project", ".", "--out-dir", ".scintilla"], env
        return [tool("scintilla"), "dev", "--project", ".", "--out-dir", ".scintilla"], env
    if stack == "ores-stack":
        if item.get("localBind"):
            env["BIND_ADDR"] = item["localBind"]
        if mode == "build":
            return [tool("ores-stack"), "check"], env
        return [tool("ores-stack"), "dev"], env
    raise SystemExit(f"unsupported stack: {stack}")


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in {"build", "run", "plan"}:
        raise SystemExit("usage: run-sibling.py build|run|plan <service>")
    mode, name = sys.argv[1], sys.argv[2]
    item = service(name)
    repo = admitted_repo(item)
    effective = "run" if mode == "plan" else mode
    argv, env = stack_command(effective, item)
    if mode == "plan":
        print(json.dumps({
            "service": name,
            "repository": repo.name,
            "stack": TOPOLOGY["stack"],
            "argv": argv,
            "localBind": item.get("localBind"),
            "healthUrl": item.get("healthUrl"),
        }, sort_keys=True))
        return 0
    if mode == "build":
        return subprocess.run(argv, cwd=repo, env=env).returncode
    os.chdir(repo)
    os.execvpe(argv[0], argv, env)
    return 127


if __name__ == "__main__":
    raise SystemExit(main())
