#!/usr/bin/env python3
from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROJECTS = sorted(
    p for p in ROOT.glob("stacks/*/projects/*")
    if p.is_dir() and (p / "contracts/projection.json").is_file()
)

for executable in ("protoc", "rustc"):
    if shutil.which(executable) is None:
        raise SystemExit(f"missing required compiler {executable}")

tsc = shutil.which("tsc")
errors: list[str] = []

with tempfile.TemporaryDirectory(prefix="ores-comparison-generated-") as tmp:
    tmpdir = Path(tmp)
    for index, project in enumerate(PROJECTS):
        rel = project.relative_to(ROOT)
        proto = project / "contracts/generated/protobuf/comparison.proto"
        rust = project / "contracts/generated/interfaces/rust.rs"
        typescript = project / "contracts/generated/interfaces/typescript.ts"
        validation = project / "contracts/generated/validation/domain.schema.json"
        authored = project / "contracts/json-schema/domain.schema.json"

        try:
            subprocess.run(
                [
                    "protoc",
                    f"--proto_path={proto.parent}",
                    f"--descriptor_set_out={tmpdir / f'{index}.pb'}",
                    str(proto),
                ],
                check=True,
            )
            subprocess.run(
                [
                    "rustc",
                    "--edition=2024",
                    "--crate-type=lib",
                    "--emit=metadata",
                    "-o",
                    str(tmpdir / f"{index}.rmeta"),
                    str(rust),
                ],
                check=True,
            )
            if tsc:
                subprocess.run(
                    [
                        tsc,
                        "--strict",
                        "--noEmit",
                        "--target",
                        "ES2022",
                        str(typescript),
                    ],
                    check=True,
                )

            if json.loads(validation.read_text()) != json.loads(authored.read_text()):
                raise AssertionError("generated validation schema diverges from admitted authored schema")

            print(f"generated interfaces OK: {rel}")
        except Exception as exc:
            errors.append(f"{rel}: {exc}")

if errors:
    print("generated interface verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("generated interface verification OK: Protobuf/Rust/TypeScript projections compile")
