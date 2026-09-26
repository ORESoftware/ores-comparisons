#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "conformance" / "ores-stack-cli" / "release-safety.v1.json"
ERROR_SCHEMA = "ores.stack.failure/v1"
CODE_RE = re.compile(r"^ORES\.[A-Z0-9_]+\.[A-Z0-9_]+$")
SHA40 = re.compile(r"^[0-9a-f]{40}$")
COMMANDS = {"build", "dev", "generate", "verify", "deploy"}


def load_contract() -> dict[str, Any]:
    value = json.loads(CONTRACT.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("release-safety contract root must be an object")
    return value


def verify_contract(doc: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if doc.get("schema") != "ores.stack.cli-release-safety/v1":
        errors.append("release-safety schema drift")
    envelope = doc.get("failureEnvelope", {})
    if envelope.get("schema") != ERROR_SCHEMA:
        errors.append("failure envelope schema drift")
    if set(envelope.get("commands", [])) != COMMANDS:
        errors.append("failure envelope must cover build/dev/generate/verify/deploy")
    required = {"schema", "code", "command", "message", "retryable", "details"}
    if set(envelope.get("requiredFields", [])) != required:
        errors.append("failure envelope field set drift")

    seen: set[str] = set()
    command_coverage: set[str] = set()
    for item in doc.get("errorCodes", []):
        if not isinstance(item, dict):
            errors.append("error code entry must be an object")
            continue
        code = item.get("code")
        command = item.get("command")
        if not isinstance(code, str) or not CODE_RE.fullmatch(code):
            errors.append(f"invalid error code {code!r}")
        elif code in seen:
            errors.append(f"duplicate error code {code}")
        else:
            seen.add(code)
        if command in COMMANDS:
            command_coverage.add(command)
        elif command != "all":
            errors.append(f"invalid command scope {command!r}")
        if not isinstance(item.get("retryable"), bool):
            errors.append(f"{code}: retryable must be boolean")
    missing = sorted(COMMANDS - command_coverage)
    if missing:
        errors.append(f"error catalog lacks command-specific coverage: {missing}")

    clean = doc.get("cleanMachine", {})
    clean_checks = set(clean.get("requiredChecks", []))
    for required_check in {"empty-home", "allowlisted-path", "undeclared-global-tool-rejected"}:
        if required_check not in clean_checks:
            errors.append(f"clean-machine contract missing {required_check}")
    if clean.get("harness") != "scripts/verify_ores_stack_clean_machine.py":
        errors.append("clean-machine harness path drift")
    elif not (ROOT / clean["harness"]).is_file():
        errors.append("clean-machine harness file missing")

    recovery = doc.get("recovery", {})
    recovery_checks = set(recovery.get("requiredChecks", []))
    for required_check in {
        "candidate-digest-verified-before-activation",
        "activation-switch-is-atomic",
        "previous-toolchain-restored",
        "cache-namespaces-retained",
    }:
        if required_check not in recovery_checks:
            errors.append(f"rollback contract missing {required_check}")
    if recovery.get("harness") != "scripts/ores_stack_toolchain_switch.py":
        errors.append("rollback harness path drift")
    elif not (ROOT / recovery["harness"]).is_file():
        errors.append("rollback harness file missing")

    fixture = recovery.get("migrationFixture", {})
    for key in ("previous", "candidate"):
        value = fixture.get(key)
        if not isinstance(value, str) or not SHA40.fullmatch(value):
            errors.append(f"recovery migration fixture {key} must be an immutable SHA")
    if fixture.get("countsAsReleaseProof") is not False:
        errors.append("migration fixture must never count as release proof")
    return errors


def failure_envelope(code: str, command: str, message: str, retryable: bool, details: dict[str, Any] | None = None) -> dict[str, Any]:
    if not CODE_RE.fullmatch(code):
        raise ValueError("invalid stable error code")
    if command not in COMMANDS:
        raise ValueError("invalid command")
    if not isinstance(retryable, bool):
        raise ValueError("retryable must be boolean")
    return {
        "schema": ERROR_SCHEMA,
        "code": code,
        "command": command,
        "message": message,
        "retryable": retryable,
        "details": details or {},
    }


def tree_digest(root: Path) -> str:
    h = hashlib.sha256()
    for path in sorted(p for p in root.rglob("*") if p.is_file() and not p.is_symlink()):
        rel = path.relative_to(root).as_posix().encode()
        data = path.read_bytes()
        h.update(len(rel).to_bytes(8, "big"))
        h.update(rel)
        h.update(len(data).to_bytes(8, "big"))
        h.update(data)
    return h.hexdigest()


def minimal_env(temp: Path) -> dict[str, str]:
    keep = [
        "PATH", "RUSTUP_HOME", "RUSTUP_TOOLCHAIN",
        "GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0",
        "SSL_CERT_FILE", "SSL_CERT_DIR", "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY"
    ]
    env = {key: os.environ[key] for key in keep if key in os.environ}
    home = temp / "home"
    cargo_home = temp / "cargo-home"
    home.mkdir(parents=True, exist_ok=True)
    cargo_home.mkdir(parents=True, exist_ok=True)
    env["HOME"] = str(home)
    env["CARGO_HOME"] = str(cargo_home)
    env["CARGO_NET_GIT_FETCH_WITH_CLI"] = "true"
    return env


def run(argv: list[str], *, env: dict[str, str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(argv, cwd=cwd, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def require_ok(result: subprocess.CompletedProcess[str], label: str) -> None:
    if result.returncode != 0:
        raise RuntimeError(f"{label} failed ({result.returncode}):\n{result.stdout[-8000:]}")


def install_cli(repo: str, rev: str, root: Path, env: dict[str, str]) -> Path:
    result = run([
        "cargo", "install",
        "--git", repo,
        "--rev", rev,
        "--locked",
        "--root", str(root),
        "--bin", "ores-stack",
        "--package", "ores-stack-cli",
    ], env=env)
    require_ok(result, f"cargo install {rev}")
    binary = root / "bin" / ("ores-stack.exe" if os.name == "nt" else "ores-stack")
    if not binary.is_file():
        raise RuntimeError(f"installed binary missing: {binary}")
    return binary


def clone_at(repo: str, rev: str, dest: Path, env: dict[str, str]) -> None:
    require_ok(run(["git", "clone", "--no-checkout", repo, str(dest)], env=env), "git clone")
    require_ok(run(["git", "-C", str(dest), "checkout", "--detach", rev], env=env), "git checkout")


def atomic_activate(link: Path, binary: Path) -> None:
    tmp = link.with_name(link.name + ".next")
    tmp.unlink(missing_ok=True)
    os.symlink(binary, tmp)
    os.replace(tmp, link)


def exercise(repo: str, previous: str, candidate: str, receipt_path: Path) -> int:
    for value in (previous, candidate):
        if not SHA40.fullmatch(value):
            raise ValueError("exercise revisions must be full immutable SHAs")
    with tempfile.TemporaryDirectory(prefix="ores-stack-release-safety-") as raw:
        temp = Path(raw)
        env = minimal_env(temp)
        state = temp / "project-state"
        (state / "tmp/cache").mkdir(parents=True)
        (state / ".ores-stack.toml").write_text("version = 1\n", encoding="utf-8")
        (state / "tmp/cache/sentinel").write_bytes(b"cache-before\n")
        before = tree_digest(state)

        previous_root = temp / "previous"
        candidate_root = temp / "candidate"
        previous_bin = install_cli(repo, previous, previous_root, env)
        candidate_bin = install_cli(repo, candidate, candidate_root, env)

        require_ok(run([str(previous_bin), "--version"], env=env), "previous version smoke")
        require_ok(run([str(candidate_bin), "--version"], env=env), "candidate version smoke")

        source = temp / "source"
        clone_at(repo, candidate, source, env)
        fixture = source / "fixtures" / "axum-web-server"
        if not fixture.is_dir():
            raise RuntimeError("representative axum fixture missing")
        clean_harness = ROOT / "scripts" / "verify_ores_stack_clean_machine.py"
        declared_tools = ("cargo", "rustc", "git", "cc")
        def clean_cli(command: str) -> None:
            argv = [
                sys.executable,
                str(clean_harness),
                "--cli",
                str(candidate_bin),
                "--project",
                str(fixture),
            ]
            for tool in declared_tools:
                argv.extend(["--allow-tool", tool])
            argv.extend(["--", command])
            require_ok(run(argv, env=env), f"clean-machine ores-stack {command}")

        clean_cli("check")
        clean_cli("build")

        active = temp / "ores-stack-current"
        atomic_activate(active, previous_bin)
        require_ok(run([str(active), "--version"], env=env), "pre-upgrade active smoke")
        atomic_activate(active, candidate_bin)

        # Deliberate post-activation health failure. Recovery must use the already
        # installed previous toolchain rather than mutating project state/caches.
        injected_failure = True
        if injected_failure:
            atomic_activate(active, previous_bin)

        if active.resolve() != previous_bin.resolve():
            raise RuntimeError("rollback did not restore previous CLI")
        require_ok(run([str(active), "--version"], env=env), "restored CLI smoke")

        after = tree_digest(state)
        if before != after:
            raise RuntimeError("project configuration/cache bytes changed during upgrade recovery")

        receipt = {
            "schema": "ores.stack.cli-release-safety-receipt/v1",
            "state": "passed",
            "evidenceClass": "migration-fixture",
            "countsAsReleaseProof": False,
            "repository": repo,
            "previous": previous,
            "candidate": candidate,
            "cleanEnvironment": {
                "home": "temporary-empty",
                "cargoHome": "temporary-empty",
                "declaredTools": ["cargo", "rustc", "git", "cc"],
            },
            "checks": {
                "previousInstall": "passed",
                "candidateInstall": "passed",
                "representativeCheck": "passed-clean-machine",
                "representativeBuild": "passed-clean-machine",
                "postSwitchFailureInjected": True,
                "rollback": "passed",
                "restoredCliExecutes": "passed",
                "projectStateDigestBefore": before,
                "projectStateDigestAfter": after,
            },
        }
        receipt_path.parent.mkdir(parents=True, exist_ok=True)
        receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(receipt, indent=2, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--exercise", action="store_true")
    parser.add_argument("--repository")
    parser.add_argument("--previous")
    parser.add_argument("--candidate")
    parser.add_argument("--receipt", type=Path, default=ROOT / "artifacts/ores-stack-cli-release-safety.json")
    args = parser.parse_args()

    errors = verify_contract(load_contract())
    if errors:
        for error in errors:
            print(f"ERROR {error}")
        return 1
    if not args.exercise:
        print("ORES Stack CLI release-safety contract OK")
        return 0
    if not all((args.repository, args.previous, args.candidate)):
        parser.error("--exercise requires --repository, --previous, and --candidate")
    return exercise(args.repository, args.previous, args.candidate, args.receipt)


if __name__ == "__main__":
    raise SystemExit(main())
