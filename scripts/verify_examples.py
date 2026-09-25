#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import tomllib

from project_matrix import ROOT, load_project_specs

REQUIRED = {
    "ores_otel", "ores_forms", "opto_sync", "ores_chat", "ores_convo",
    "ores_rate_limit", "ores_middleware", "ores_redis_lru_cache",
    "api_docs", "ores_sops_legacy", "ores_sops_org",
}
CONTRACT_FILES = (
    "contracts/typespec/main.tsp",
    "contracts/json-schema/domain.schema.json",
    "contracts/projection.json",
    "contracts/generated/sql/001_init.sql",
    "contracts/generated/sql/002_seed.sql",
    "contracts/generated/sql/010_domain_constraints.sql",
    "contracts/generated/protobuf/comparison.proto",
    "contracts/generated/protobuf/domain.proto",
    "contracts/generated/interfaces/typescript.ts",
    "contracts/generated/interfaces/rust.rs",
    "contracts/generated/interfaces/gleam.gleam",
    "contracts/generated/validation/domain.schema.json",
    "conformance/check.sh",
    "governance/authority-contract.json",
    "governance/README.md",
)
LOCAL_FILES = (
    ".ores-compose.yaml", ".zpkg.toml",
    "scripts/postgres-local.sh", "scripts/db-health.sh",
    "scripts/load-env.sh", "scripts/db-migrate.sh",
    "scripts/db-seed.sh", "scripts/contracts-check.sh",
    "scripts/dev-server.sh",
)

errors: list[str] = []

try:
    specs = load_project_specs()
except Exception as exc:
    raise SystemExit(f"invalid project matrix: {exc}") from exc

expected_paths = {spec.path for spec in specs}
actual_paths = {
    p for p in ROOT.glob("stacks/*/projects/*")
    if p.is_dir() and (p / "comparison.toml").is_file()
}
for missing in sorted(expected_paths - actual_paths):
    errors.append(f"matrix project is missing: {missing.relative_to(ROOT)}")
for ungoverned in sorted(actual_paths - expected_paths):
    errors.append(f"project exists outside shared/project-matrix.json: {ungoverned.relative_to(ROOT)}")

shared = json.loads((ROOT / "shared/integrations.json").read_text())
shared_ids = {x["id"] for x in shared["integrations"]}
if shared_ids != REQUIRED:
    errors.append(f"shared integration IDs differ: {shared_ids ^ REQUIRED}")

seen_ports: dict[str, set[int]] = {}
for spec in specs:
    stack = spec.stack
    scenario = spec.scenario
    p = spec.path
    stack_ports = seen_ports.setdefault(stack, set())
    if not p.is_dir():
        continue

    required_files = [
        "README.md", "comparison.toml", ".sops.yaml", ".env.example",
        "env/enc/README.md", "env/dec/.gitignore", *LOCAL_FILES,
    ]
    if spec.contracts:
        required_files.extend(CONTRACT_FILES)
    for rel in required_files:
        if not (p / rel).is_file():
            errors.append(f"{p.relative_to(ROOT)} missing {rel}")

    try:
        cfg = tomllib.loads((p / "comparison.toml").read_text())
        ids = set(cfg.get("integrations", []))
        if ids != REQUIRED:
            errors.append(
                f"{p.relative_to(ROOT)} integration drift: {sorted(ids ^ REQUIRED)}"
            )
        if cfg.get("stack") != stack or cfg.get("scenario") != scenario:
            errors.append(f"{p.relative_to(ROOT)} identity mismatch")
    except Exception as exc:
        errors.append(f"{p.relative_to(ROOT)} bad comparison.toml: {exc}")

    if spec.contracts:
        try:
            schema = json.loads((p / "contracts/json-schema/domain.schema.json").read_text())
            generated = json.loads((p / "contracts/generated/validation/domain.schema.json").read_text())
            if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
                errors.append(f"{p.relative_to(ROOT)} authored schema is not Draft 2020-12")
            if not schema.get("$defs"):
                errors.append(f"{p.relative_to(ROOT)} authored schema has no declarations")
            if schema != generated:
                errors.append(f"{p.relative_to(ROOT)} runtime validation projection drift")
        except Exception as exc:
            errors.append(f"{p.relative_to(ROOT)} invalid schema: {exc}")

        try:
            governance = json.loads((p / "governance/authority-contract.json").read_text())
            if governance.get("schema") != "ores.comparisons.authority-contract/v1":
                errors.append(f"{p.relative_to(ROOT)} governance schema drift")
            gate = governance.get("parity_gate", {})
            if gate.get("version") != "0.1.1" or gate.get("commit") != "e29a91d7ef74e3b0613ea79e988bec4c467535d2":
                errors.append(f"{p.relative_to(ROOT)} parity gate is not exactly pinned")
        except Exception as exc:
            errors.append(f"{p.relative_to(ROOT)} invalid governance contract: {exc}")

    sops = (p / ".sops.yaml").read_text()
    for exact in (
        r"^env/enc/dev\.env\.enc$",
        r"^env/enc/stage\.env\.enc$",
        r"^env/enc/prod\.env\.enc$",
    ):
        if exact not in sops:
            errors.append(f"{p.relative_to(ROOT)} missing SOPS rule {exact}")

    dec_entries = [x.name for x in (p / "env/dec").iterdir()] if (p / "env/dec").is_dir() else []
    if dec_entries != [".gitignore"]:
        errors.append(f"{p.relative_to(ROOT)} tracks unexpected env/dec content: {dec_entries}")

    compose = (p / ".ores-compose.yaml").read_text()
    for needle in (
        "schema_version: ores.compose.v1",
        "postgres:",
        'command: ["bash", "scripts/postgres-local.sh"]',
        '["bash", "scripts/db-migrate.sh"]',
        '["bash", "scripts/db-seed.sh"]',
        "depends_on:",
    ):
        if needle not in compose:
            errors.append(f"{p.relative_to(ROOT)} compose missing {needle}")
    for match in re.finditer(r"retries:\s*(\d+)", compose):
        if int(match.group(1)) > 20:
            errors.append(f"{p.relative_to(ROOT)} compose health retries exceed ores-compose v1 limit")
    env = (p / ".env.example").read_text()
    port_match = re.search(r"^PGPORT=(\d+)$", env, re.MULTILINE)
    if not port_match:
        errors.append(f"{p.relative_to(ROOT)} .env.example missing PGPORT")
    else:
        port = int(port_match.group(1))
        if port in stack_ports:
            errors.append(f"{stack} reuses local postgres port {port}")
        stack_ports.add(port)
    if "DATABASE_URL=postgresql://postgres@127.0.0.1:" not in env:
        errors.append(f"{p.relative_to(ROOT)} missing local DATABASE_URL")

    if spec.contracts:
        migration = (p / "contracts/generated/sql/001_init.sql").read_text()
        domains = (p / "contracts/generated/sql/010_domain_constraints.sql").read_text()
        seed = (p / "contracts/generated/sql/002_seed.sql").read_text()
        if "CREATE TABLE IF NOT EXISTS" not in migration:
            errors.append(f"{p.relative_to(ROOT)} generated migration is not idempotent")
        if "IF NOT EXISTS (" not in domains or "ADD CONSTRAINT" not in domains:
            errors.append(f"{p.relative_to(ROOT)} generated domain migration is not additive/idempotent")
        if "010_domain_constraints.sql" not in (p / "scripts/db-migrate.sh").read_text():
            errors.append(f"{p.relative_to(ROOT)} dev migration script omits domain constraints")
        if "ON CONFLICT DO NOTHING" not in seed:
            errors.append(f"{p.relative_to(ROOT)} generated seed is not idempotent")

    if stack == "beamscale":
        if not (p / ".ores-lambda.toml").is_file():
            errors.append(f"{p.relative_to(ROOT)} missing .ores-lambda.toml")
        if not list((p / "lambdas").glob("**/gleam.toml")):
            errors.append(f"{p.relative_to(ROOT)} has no BeamScale lambda project")
    elif stack == "scintilla-run":
        endpoints = list(p.glob("**/.scintilla-endpoint.toml"))
        if not endpoints:
            errors.append(f"{p.relative_to(ROOT)} has no Scintilla endpoint")
        for rel in ("scripts/scintilla-runtime-build.sh", "scripts/scintilla-runner.sh", "scripts/scintilla-backend.sh"):
            if not (p / rel).is_file():
                errors.append(f"{p.relative_to(ROOT)} missing {rel}")
    elif stack == "ores-stack":
        for rel in (".ores-stack.toml", "Cargo.toml", "build.rs", "contracts/service.route-map.json"):
            if not (p / rel).is_file():
                errors.append(f"{p.relative_to(ROOT)} missing {rel}")
    else:
        errors.append(f"unknown stack in project matrix: {stack}")

for f in ROOT.rglob("*"):
    if f.is_file() and ".git" not in f.parts:
        try:
            text = f.read_text()
        except UnicodeDecodeError:
            continue
        if re.search(r"AGE-SECRET-KEY-1[0-9A-Z]{40,}", text):
            errors.append(f"private age identity committed in {f.relative_to(ROOT)}")

if errors:
    print("comparison verification FAILED")
    for error in errors:
        print(f" - {error}")
    raise SystemExit(1)

print(f"comparison verification OK: {len(specs)} matrix-governed projects have required contracts, postgres, compose and secret boundaries")
