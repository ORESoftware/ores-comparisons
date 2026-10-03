#!/usr/bin/env python3
"""Digest-verified atomic ORES Stack CLI activation with rollback on smoke failure."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


@dataclass(frozen=True)
class Toolchain:
    identity: str
    binary: Path
    sha256: str


class ToolchainManager:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.bin_dir = root / "bin"
        self.cache_root = root / "cache"
        self.state_path = root / "state.json"
        self.active_path = self.bin_dir / "ores-stack"

    def prepare(self) -> None:
        self.bin_dir.mkdir(parents=True, exist_ok=True)
        self.cache_root.mkdir(parents=True, exist_ok=True)

    @staticmethod
    def _sha256(path: Path) -> str:
        hasher = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                hasher.update(chunk)
        return hasher.hexdigest()

    @staticmethod
    def verify_toolchain(toolchain: Toolchain) -> None:
        if not toolchain.identity or "/" in toolchain.identity or "\\" in toolchain.identity:
            raise ValueError("toolchain identity must be a non-empty single path component")
        if len(toolchain.sha256) != 64 or any(ch not in "0123456789abcdef" for ch in toolchain.sha256):
            raise ValueError("toolchain sha256 must be lowercase 64-hex")
        info = toolchain.binary.lstat()
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
            raise ValueError("toolchain binary must be a regular non-symlink file")
        actual = ToolchainManager._sha256(toolchain.binary)
        if actual != toolchain.sha256:
            raise ValueError(
                f"toolchain digest mismatch: expected {toolchain.sha256}, got {actual}"
            )

    def _load_state(self) -> dict[str, object]:
        if not self.state_path.exists():
            return {"schema": "ores.stack.toolchain-state/v1", "current": None, "previous": None}
        value = json.loads(self.state_path.read_text(encoding="utf-8"))
        if value.get("schema") != "ores.stack.toolchain-state/v1":
            raise ValueError("unsupported toolchain state schema")
        return value

    def _write_state(self, state: dict[str, object]) -> None:
        tmp = self.state_path.with_name(f".{self.state_path.name}.{os.getpid()}.tmp")
        payload = json.dumps(state, indent=2, sort_keys=True) + "\n"
        with tmp.open("x", encoding="utf-8") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, self.state_path)

    def _replace_active(self, binary: Path) -> None:
        tmp = self.bin_dir / f".ores-stack.{os.getpid()}.tmp"
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass
        os.symlink(binary.resolve(), tmp)
        os.replace(tmp, self.active_path)

    @staticmethod
    def _state_entry(toolchain: Toolchain) -> dict[str, str]:
        return {
            "identity": toolchain.identity,
            "binary": str(toolchain.binary.resolve()),
            "sha256": toolchain.sha256,
        }

    @staticmethod
    def _entry_toolchain(entry: object) -> Toolchain | None:
        if not isinstance(entry, dict):
            return None
        identity = entry.get("identity")
        binary = entry.get("binary")
        sha256 = entry.get("sha256")
        if not all(isinstance(value, str) for value in (identity, binary, sha256)):
            raise ValueError("invalid toolchain state entry")
        return Toolchain(identity=identity, binary=Path(binary), sha256=sha256)

    def activate(
        self,
        candidate: Toolchain,
        *,
        smoke_argv: Sequence[str] | None = None,
        smoke_cwd: Path | None = None,
    ) -> bool:
        self.prepare()
        self.verify_toolchain(candidate)
        before = self._load_state()
        previous = self._entry_toolchain(before.get("current"))
        if previous is not None:
            self.verify_toolchain(previous)

        # Cache namespaces are append-only from the switcher's perspective.
        (self.cache_root / candidate.identity).mkdir(parents=True, exist_ok=True)

        self._replace_active(candidate.binary)
        next_state = {
            "schema": "ores.stack.toolchain-state/v1",
            "current": self._state_entry(candidate),
            "previous": self._state_entry(previous) if previous else None,
        }
        self._write_state(next_state)

        if smoke_argv:
            completed = subprocess.run(
                list(smoke_argv),
                cwd=smoke_cwd,
                stdin=subprocess.DEVNULL,
                check=False,
            )
            if completed.returncode != 0:
                if previous is None:
                    try:
                        self.active_path.unlink()
                    except FileNotFoundError:
                        pass
                else:
                    self._replace_active(previous.binary)
                self._write_state(before)
                return False
        return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--identity", required=True)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--smoke-cwd", type=Path)
    parser.add_argument("smoke", nargs=argparse.REMAINDER)
    args = parser.parse_args()

    candidate = Toolchain(
        identity=args.identity,
        binary=args.binary,
        sha256=args.sha256,
    )
    manager = ToolchainManager(args.root)
    smoke = args.smoke
    if smoke and smoke[0] == "--":
        smoke = smoke[1:]
    ok = manager.activate(
        candidate,
        smoke_argv=smoke or None,
        smoke_cwd=args.smoke_cwd,
    )
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
