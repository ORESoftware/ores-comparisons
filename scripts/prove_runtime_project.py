#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import queue
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import TextIO

ROOT = Path(__file__).resolve().parents[1]
STACKS = ("beamscale", "scintilla-run", "ores-stack")
SCENARIOS = (
    "cached-rpc",
    "http-observability",
    "forms-chat-workflow",
    "big-org-example-commerce",
    "big-org-example-collaboration",
    "big-org-example-operations",
)


def revision(root: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except Exception:
        return "unknown"


def admitted_project(root: Path, stack: str, scenario: str) -> dict[str, object]:
    matrix_path = root / "shared" / "project-matrix.json"
    matrix = json.loads(matrix_path.read_text())
    matches = [
        item
        for item in matrix.get("projects", [])
        if item.get("stack") == stack and item.get("scenario") == scenario
    ]
    if len(matches) != 1:
        raise RuntimeError(
            f"expected exactly one matrix entry for {stack}/{scenario}, found {len(matches)}"
        )
    return matches[0]


def reader_thread(stream: TextIO, output: queue.Queue[str | None]) -> None:
    try:
        for line in stream:
            output.put(line)
    finally:
        output.put(None)


def stop_process(process: subprocess.Popen[str], *, force: bool = False) -> None:
    if process.poll() is not None:
        return
    if force and os.name == "posix":
        try:
            os.killpg(process.pid, signal.SIGKILL)
            return
        except ProcessLookupError:
            return
    try:
        if force:
            process.kill()
        else:
            process.send_signal(signal.SIGINT)
    except ProcessLookupError:
        return


def prove_runtime(
    *,
    root: Path,
    stack: str,
    scenario: str,
    compose: Path,
    ready_timeout: float,
    shutdown_timeout: float,
    output_dir: Path,
) -> Path:
    if stack not in STACKS:
        raise RuntimeError(f"unsupported stack: {stack}")
    if scenario not in SCENARIOS:
        raise RuntimeError(f"unsupported scenario: {scenario}")
    admitted_project(root, stack, scenario)

    manifest = (
        root
        / "stacks"
        / stack
        / "projects"
        / scenario
        / "repos"
        / ".github"
        / ".ores-compose.yaml"
    )
    if not manifest.is_file():
        raise RuntimeError(
            f"compose manifest is unavailable at {manifest}; initialize the target private submodules first"
        )
    if not compose.is_file():
        raise RuntimeError(f"ores-compose binary is unavailable at {compose}")

    output_dir.mkdir(parents=True, exist_ok=True)
    log_path = output_dir / f"{stack}-{scenario}.log"
    receipt_path = output_dir / f"{stack}-{scenario}.json"
    command = [str(compose), "up", str(manifest)]
    started_at = time.time()
    ready_event: dict[str, object] | None = None
    process: subprocess.Popen[str] | None = None
    status = "failed"
    error: str | None = None
    return_code: int | None = None

    try:
        process = subprocess.Popen(
            command,
            cwd=root,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            start_new_session=(os.name == "posix"),
        )
        assert process.stdout is not None
        lines: queue.Queue[str | None] = queue.Queue()
        reader = threading.Thread(
            target=reader_thread,
            args=(process.stdout, lines),
            daemon=True,
        )
        reader.start()

        deadline = time.monotonic() + ready_timeout
        with log_path.open("w") as log:
            while ready_event is None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise RuntimeError(
                        f"timed out after {ready_timeout:.0f}s waiting for compose_ready"
                    )
                try:
                    line = lines.get(timeout=min(0.25, remaining))
                except queue.Empty:
                    if process.poll() is not None:
                        raise RuntimeError(
                            f"ores-compose exited with {process.returncode} before compose_ready"
                        )
                    continue

                if line is None:
                    if process.poll() is not None and ready_event is None:
                        raise RuntimeError(
                            f"ores-compose exited with {process.returncode} before compose_ready"
                        )
                    continue

                sys.stdout.write(line)
                sys.stdout.flush()
                log.write(line)
                log.flush()
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if (
                    isinstance(event, dict)
                    and event.get("event") == "compose_ready"
                ):
                    ready_event = event

            # The pinned foreground executor treats SIGINT as the normal drain
            # boundary, shuts down its dependency graph, and returns success.
            stop_process(process)
            try:
                return_code = process.wait(timeout=shutdown_timeout)
            except subprocess.TimeoutExpired as exc:
                stop_process(process, force=True)
                process.wait(timeout=5)
                raise RuntimeError(
                    f"ores-compose did not shut down within {shutdown_timeout:.0f}s after compose_ready"
                ) from exc
            if return_code != 0:
                raise RuntimeError(
                    f"ores-compose returned {return_code} after compose_ready and shutdown"
                )
            status = "passed"
    except Exception as exc:
        error = str(exc)
        if process is not None and process.poll() is None:
            stop_process(process)
            try:
                return_code = process.wait(timeout=min(shutdown_timeout, 10))
            except subprocess.TimeoutExpired:
                stop_process(process, force=True)
                try:
                    return_code = process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    return_code = None
        raise
    finally:
        receipt: dict[str, object] = {
            "schema": "ores.comparisons.runtime-proof/v1",
            "stack": stack,
            "scenario": scenario,
            "revision": revision(root),
            "manifest": str(manifest.relative_to(root)),
            "command": ["ores-compose", "up", str(manifest.relative_to(root))],
            "status": status,
            "composeReady": ready_event is not None,
            "returnCode": return_code,
            "durationSeconds": round(time.time() - started_at, 3),
            "log": str(log_path.relative_to(root))
            if log_path.is_relative_to(root)
            else str(log_path),
        }
        if ready_event is not None:
            receipt["readyEvent"] = ready_event
        if error is not None:
            receipt["error"] = error
        receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        print(json.dumps(receipt, sort_keys=True))

    return receipt_path


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Prove one ores-comparisons project reaches ores-compose compose_ready."
    )
    parser.add_argument("--stack", required=True, choices=STACKS)
    parser.add_argument("--scenario", required=True, choices=SCENARIOS)
    parser.add_argument(
        "--compose",
        default=str(ROOT / ".local" / "bin" / "ores-compose"),
    )
    parser.add_argument("--ready-timeout", type=float, default=300)
    parser.add_argument("--shutdown-timeout", type=float, default=30)
    parser.add_argument(
        "--output-dir",
        default=str(ROOT / "artifacts" / "runtime-18"),
    )
    args = parser.parse_args()

    try:
        prove_runtime(
            root=ROOT,
            stack=args.stack,
            scenario=args.scenario,
            compose=Path(args.compose).resolve(),
            ready_timeout=args.ready_timeout,
            shutdown_timeout=args.shutdown_timeout,
            output_dir=Path(args.output_dir).resolve(),
        )
    except Exception as exc:
        print(f"runtime proof failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
