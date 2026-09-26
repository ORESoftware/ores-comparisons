#!/usr/bin/env python3
"""Run ORES Stack smoke commands in an empty-HOME, allowlisted-PATH environment."""

from __future__ import annotations

import argparse
import os
import shutil
import stat
import subprocess
import tempfile
from pathlib import Path
from typing import Sequence


def require_regular_binary(path: Path, label: str) -> Path:
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise ValueError(f"{label} must be a regular non-symlink file")
    return path.resolve()


def isolated_path(bin_dir: Path, cli: Path, allowed_tools: Sequence[str]) -> None:
    bin_dir.mkdir(parents=True, exist_ok=True)
    os.symlink(require_regular_binary(cli, "ores-stack CLI"), bin_dir / "ores-stack")
    for tool in allowed_tools:
        if not tool or "/" in tool or "\\" in tool:
            raise ValueError(f"invalid allowed tool name {tool!r}")
        resolved = shutil.which(tool)
        if resolved is None:
            raise ValueError(f"declared bootstrap tool {tool!r} is unavailable on the host")
        target = require_regular_binary(Path(resolved), f"bootstrap tool {tool}")
        os.symlink(target, bin_dir / tool)


def run_clean_machine(
    cli: Path,
    project: Path,
    argv: Sequence[str],
    *,
    allowed_tools: Sequence[str],
) -> subprocess.CompletedProcess[bytes]:
    if not project.is_dir():
        raise ValueError("project must be an existing directory")
    with tempfile.TemporaryDirectory(prefix="ores-stack-clean-machine-") as temp:
        temp_root = Path(temp)
        home = temp_root / "home"
        bin_dir = temp_root / "bin"
        home.mkdir()
        isolated_path(bin_dir, cli, allowed_tools)
        env = {
            "HOME": str(home),
            "PATH": str(bin_dir),
            "LANG": "C",
            "LC_ALL": "C",
            "NO_COLOR": "1",
        }
        return subprocess.run(
            [str(bin_dir / "ores-stack"), *argv],
            cwd=project,
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli", type=Path, required=True)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--allow-tool", action="append", default=[])
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        parser.error("a CLI command is required after --")

    result = run_clean_machine(
        args.cli,
        args.project,
        command,
        allowed_tools=args.allow_tool,
    )
    os.write(1, result.stdout)
    os.write(2, result.stderr)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
