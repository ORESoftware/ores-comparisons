#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HTTPS = re.compile(r"^https://github\.com/([^/]+)/([^/]+?)(?:\.git)?/?$")
SSH = re.compile(r"^(?:git@github\.com:|ssh://git@github\.com/)([^/]+)/([^/]+?)(?:\.git)?/?$")
URL_LINE = re.compile(r"^\s*url\s*=\s*(\S+)\s*$")


def canonical_repository(value: str) -> tuple[str, str]:
    value = value.strip()
    match = HTTPS.fullmatch(value) or SSH.fullmatch(value)
    if match is None:
        raise RuntimeError(f"unsupported non-GitHub repository URL: {value!r}")
    owner, repo = match.groups()
    if not owner or not repo or any(part in {".", ".."} for part in (owner, repo)):
        raise RuntimeError(f"invalid GitHub repository URL: {value!r}")
    identity = f"{owner}/{repo}"
    return identity, f"https://github.com/{identity}"


def required_repositories(root: Path) -> dict[str, str]:
    urls: list[str] = []

    gitmodules = root / ".gitmodules"
    if not gitmodules.is_file():
        raise RuntimeError(".gitmodules is missing")
    for line in gitmodules.read_text().splitlines():
        match = URL_LINE.fullmatch(line)
        if match is not None:
            urls.append(match.group(1))

    lock_path = root / "tools" / "toolchain.lock.json"
    lock = json.loads(lock_path.read_text())
    tools = lock.get("tools")
    if not isinstance(tools, dict):
        raise RuntimeError("toolchain lock has no tools map")
    for name, entry in tools.items():
        if not isinstance(entry, dict):
            raise RuntimeError(f"toolchain entry {name!r} is not an object")
        repository = entry.get("repository")
        if not isinstance(repository, str) or not repository:
            raise RuntimeError(f"toolchain entry {name!r} has no repository")
        urls.append(repository)

    repositories: dict[str, str] = {}
    for value in urls:
        identity, url = canonical_repository(value)
        previous = repositories.setdefault(identity, url)
        if previous != url:
            raise RuntimeError(f"repository {identity} has inconsistent canonical URLs")
    if not repositories:
        raise RuntimeError("no governed repositories were discovered")
    return dict(sorted(repositories.items()))


def authenticated_git_env(token: str) -> dict[str, str]:
    if not token or any(ch.isspace() for ch in token):
        raise RuntimeError("cross-repository read credential is missing or malformed")
    basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    env = os.environ.copy()
    env.update(
        {
            "GIT_CONFIG_COUNT": "1",
            "GIT_CONFIG_KEY_0": "http.https://github.com/.extraheader",
            "GIT_CONFIG_VALUE_0": f"AUTHORIZATION: basic {basic}",
            "GIT_TERMINAL_PROMPT": "0",
        }
    )
    return env


def probe_repository(identity: str, url: str, token: str, timeout: float) -> None:
    try:
        result = subprocess.run(
            ["git", "ls-remote", "--exit-code", url, "HEAD"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=authenticated_git_env(token),
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise RuntimeError(f"{identity}: Git read probe timed out") from error
    except OSError as error:
        raise RuntimeError(f"{identity}: could not execute Git read probe") from error
    if result.returncode != 0:
        raise RuntimeError(f"{identity}: Git read access failed with status {result.returncode}")


def verify_read_access(
    *,
    root: Path,
    token: str,
    timeout: float = 20,
    workers: int = 8,
) -> list[str]:
    repositories = required_repositories(root)
    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=max(1, min(workers, len(repositories)))) as pool:
        futures = {
            pool.submit(probe_repository, identity, url, token, timeout): identity
            for identity, url in repositories.items()
        }
        for future in as_completed(futures):
            identity = futures[future]
            try:
                future.result()
            except Exception as error:
                failures.append(str(error))
    if failures:
        failures.sort()
        raise RuntimeError(
            "cross-repository credential cannot read every governed repository:\n - "
            + "\n - ".join(failures)
        )
    return list(repositories)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Fail closed unless one credential can Git-read every governed private-authority repository."
    )
    parser.add_argument(
        "--token-env",
        default="CROSS_REPO_READ_TOKEN",
        help="environment variable containing the read credential",
    )
    parser.add_argument("--timeout", type=float, default=20)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()
    token = os.environ.get(args.token_env, "")
    try:
        repositories = verify_read_access(
            root=ROOT,
            token=token,
            timeout=args.timeout,
            workers=args.workers,
        )
    except Exception as error:
        print(f"private repository read preflight FAILED: {error}")
        return 1

    print(
        "private repository read preflight OK: "
        f"{len(repositories)} governed repositories are Git-readable"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
