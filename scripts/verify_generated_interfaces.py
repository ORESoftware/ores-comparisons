#!/usr/bin/env python3
from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
from pathlib import Path

from project_matrix import ROOT, contract_project_specs

PROJECTS = [spec.path for spec in contract_project_specs()]
if not PROJECTS:
    raise SystemExit("project matrix contains no contract-enabled projects")

for executable in ("protoc", "rustc", "tsc"):
    if shutil.which(executable) is None:
        raise SystemExit(f"missing required compiler {executable}")

errors: list[str] = []

with tempfile.TemporaryDirectory(prefix="ores-comparison-generated-") as tmp:
    tmpdir = Path(tmp)
    for index, project in enumerate(PROJECTS):
        rel = project.relative_to(ROOT)
        proto_dir = project / "contracts/generated/protobuf"
        protos = sorted(proto_dir.glob("*.proto"))
        rust = project / "contracts/generated/interfaces/rust.rs"
        typescript = project / "contracts/generated/interfaces/typescript.ts"
        gleam = project / "contracts/generated/interfaces/gleam.gleam"
        validation = project / "contracts/generated/validation/domain.schema.json"
        authored = project / "contracts/json-schema/domain.schema.json"
        projection = project / "contracts/projection.json"
        repos_readme = project / "repos/readme.md"

        try:
            for required in (projection, rust, typescript, gleam, validation, authored, repos_readme):
                if not required.is_file():
                    raise FileNotFoundError(f"missing governed artifact: {required.relative_to(ROOT)}")
            if {path.name for path in protos} != {"comparison.proto", "domain.proto"}:
                raise AssertionError(
                    f"unexpected protobuf projection set: {[path.name for path in protos]}"
                )
            subprocess.run(
                [
                    "protoc",
                    f"--proto_path={proto_dir}",
                    f"--descriptor_set_out={tmpdir / f'{index}.pb'}",
                    *[str(path) for path in protos],
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
            subprocess.run(
                [
                    "tsc",
                    "--strict",
                    "--noEmit",
                    "--target",
                    "ES2022",
                    str(typescript),
                ],
                check=True,
            )

            gleam_bin = shutil.which("gleam")
            if gleam_bin:
                gleam_root = tmpdir / f"gleam-{index}"
                source = gleam_root / "src"
                source.mkdir(parents=True)
                (gleam_root / "gleam.toml").write_text(
                    f'name = "generated_{index}"\nversion = "0.1.0"\n'
                )
                (source / "domain.gleam").write_text(gleam.read_text())
                subprocess.run([gleam_bin, "check"], cwd=gleam_root, check=True)

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

print(f"generated interface verification OK: {len(PROJECTS)} matrix-governed service/domain Protobuf and typed language projections compile")
