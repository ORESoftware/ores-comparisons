#!/usr/bin/env python3
from __future__ import annotations
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
lock = json.loads((ROOT / "tools/toolchain.lock.json").read_text())
errors = []

required = {
    "zed-cli": ("0.3.0", "17412050a0f4c4f963008f0de9abf3651d42af08"),
    "ores-compose": ("0.1.0", "fbfad966f9770a9a8d3895880523280324b4ddc6"),
    "typespec-json-schema-validator": ("0.1.1", "e29a91d7ef74e3b0613ea79e988bec4c467535d2"),
    "bmscl-cli": (None, "2a9dd1bf8835ec59c84362ac756839730fbbc7f4"),
    "bmscl-compiler": (None, "2c9e0f9d47b3d15e8ffe571c86859132d90286be"),
    "bmscl-supervisor": (None, "3c1ed4d9ab4e2d75d1430c376f1a85facf9a0212"),
    "scintilla-cli": ("0.1.0", "293620b178585418aa3fb05e14edfb6ba18fbd01"),
    "scintilla-runner": (None, "4724bbe89eeeef6f38cd112be4a5107d53874509"),
    "scintilla-backend": (None, "ac28d4a6faf3eb991b596a6a7a4f12615589bcad"),
    "ores-stack": ("0.1.0", "3915e7088cba0a77d3101c82ff14706c58870077"),
    "ores-clis-core": ("0.1.1", "e42c7ae533562a796c945070e71a8ce0f45628bf"),
}
if lock.get("schema") != "ores.comparisons.toolchain-lock/v1":
    errors.append("unexpected toolchain lock schema")

tools = lock.get("tools", {})
for name, (version, commit) in required.items():
    item = tools.get(name)
    if not item:
        errors.append(f"missing tool pin {name}")
        continue
    if item.get("commit") != commit or not re.fullmatch(r"[0-9a-f]{40}", item.get("commit", "")):
        errors.append(f"{name} commit pin drift")
    if version is not None and item.get("version") != version:
        errors.append(f"{name} version pin drift")

zpkg = (ROOT / ".zpkg.toml").read_text()
for needle in (
    '"oresoftware/ores-compose" = "=0.1.0"',
    '"oresoftware/typespec-json-schema-validator" = "=0.1.1"',
    "[interop.git]",
    "consume_gitmodules = true",
):
    if needle not in zpkg:
        errors.append(f"root .zpkg.toml missing {needle}")

pkg = json.loads((ROOT / "package.json").read_text())
private = pkg.get("oresPrivateTools", {}).get("typespec-json-schema-validator", {})
if private.get("version") != "0.1.1":
    errors.append("package.json private tjsv version drift")
if private.get("commit") != required["typespec-json-schema-validator"][1]:
    errors.append("package.json private tjsv commit drift")

if errors:
    print("toolchain pin verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("toolchain pin verification OK")
