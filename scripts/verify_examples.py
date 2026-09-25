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
shared_integrations = json.loads((ROOT / "shared/integrations.json").read_text())
if {x["id"] for x in shared_integrations["integrations"]} != REQUIRED:
    errors.append("shared integration IDs drifted")

specs = load_project_specs()
seen_ports: dict[str, set[int]] = {}

for spec in specs:
    project = spec.path
    shared = spec.shared_repo_path
    app = spec.app_repo_path
    rel = project.relative_to(ROOT)
    seen_ports.setdefault(spec.stack, set())

    if not (spec.repos_path / "readme.md").is_file():
        errors.append(f"{rel} missing repos/readme.md")

    for required in (
        "README.md", "profile/README.md", "comparison.toml",
        ".sops.yaml", ".env.example", "env/enc/README.md", "env/dec/.gitignore",
        *LOCAL_FILES,
    ):
        if not (shared / required).is_file():
            errors.append(f"{rel} repos/.github missing {required}")

    if spec.contracts:
        for required in CONTRACT_FILES:
            if not (shared / required).is_file():
                errors.append(f"{rel} repos/.github missing {required}")

    try:
        cfg = tomllib.loads((shared / "comparison.toml").read_text())
        ids = set(cfg.get("integrations", []))
        if ids != REQUIRED:
            errors.append(f"{rel} integration drift: {sorted(ids ^ REQUIRED)}")
        if cfg.get("stack") != spec.stack or cfg.get("scenario") != spec.scenario:
            errors.append(f"{rel} identity mismatch")
    except Exception as exc:
        errors.append(f"{rel} bad repos/.github/comparison.toml: {exc}")

    if spec.contracts:
        try:
            schema = json.loads((shared / "contracts/json-schema/domain.schema.json").read_text())
            generated = json.loads((shared / "contracts/generated/validation/domain.schema.json").read_text())
            if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
                errors.append(f"{rel} authored schema is not Draft 2020-12")
            if not schema.get("$defs"):
                errors.append(f"{rel} authored schema has no declarations")
            if schema != generated:
                errors.append(f"{rel} runtime validation projection drift")
        except Exception as exc:
            errors.append(f"{rel} invalid schema: {exc}")

        migration = (shared / "contracts/generated/sql/001_init.sql").read_text()
        domains = (shared / "contracts/generated/sql/010_domain_constraints.sql").read_text()
        seed = (shared / "contracts/generated/sql/002_seed.sql").read_text()
        if "CREATE TABLE IF NOT EXISTS" not in migration:
            errors.append(f"{rel} generated migration is not idempotent")
        if "IF NOT EXISTS (" not in domains or "ADD CONSTRAINT" not in domains:
            errors.append(f"{rel} generated domain migration is not additive/idempotent")
        if "010_domain_constraints.sql" not in (shared / "scripts/db-migrate.sh").read_text():
            errors.append(f"{rel} dev migration script omits domain constraints")
        if "ON CONFLICT DO NOTHING" not in seed:
            errors.append(f"{rel} generated seed is not idempotent")

    sops = (shared / ".sops.yaml").read_text()
    for exact in (
        r"^env/enc/dev\.env\.enc$",
        r"^env/enc/stage\.env\.enc$",
        r"^env/enc/prod\.env\.enc$",
    ):
        if exact not in sops:
            errors.append(f"{rel} missing SOPS rule {exact}")

    compose = (shared / ".ores-compose.yaml").read_text()
    for needle in (
        "schema_version: ores.compose.v1",
        "postgres:",
        'command: ["bash", "scripts/postgres-local.sh"]',
        '["bash", "scripts/db-migrate.sh"]',
        '["bash", "scripts/db-seed.sh"]',
        "depends_on:",
    ):
        if needle not in compose:
            errors.append(f"{rel} compose missing {needle}")
    for match in re.finditer(r"retries:\s*(\d+)", compose):
        if int(match.group(1)) > 20:
            errors.append(f"{rel} compose health retries exceed ores-compose v1 limit")

    env = (shared / ".env.example").read_text()
    port_match = re.search(r"^PGPORT=(\d+)$", env, re.MULTILINE)
    if not port_match:
        errors.append(f"{rel} .env.example missing PGPORT")
    else:
        port = int(port_match.group(1))
        if port in seen_ports[spec.stack]:
            errors.append(f"{spec.stack} reuses local postgres port {port}")
        seen_ports[spec.stack].add(port)

    if spec.stack == "beamscale":
        if not (app / ".ores-lambda.toml").is_file():
            errors.append(f"{rel} repos/app missing .ores-lambda.toml")
        if not list((app / "lambdas").glob("**/gleam.toml")):
            errors.append(f"{rel} repos/app has no BeamScale lambda project")
    elif spec.stack == "scintilla-run":
        if not list((app / "endpoints").glob("**/.scintilla-endpoint.toml")):
            errors.append(f"{rel} repos/app has no Scintilla endpoint")
        for required in (
            "scripts/scintilla-runtime-build.sh",
            "scripts/scintilla-runner.sh",
            "scripts/scintilla-backend.sh",
        ):
            if not (shared / required).is_file():
                errors.append(f"{rel} repos/.github missing {required}")
    elif spec.stack == "ores-stack":
        for required in (".ores-stack.toml", "Cargo.toml", "contracts/service.route-map.json"):
            if not (app / required).is_file():
                errors.append(f"{rel} repos/app missing {required}")

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
        print(" -", error)
    raise SystemExit(1)

print(
    f"comparison verification OK: {len(specs)} projects preserve contracts, compose, "
    "secrets, repos/.github, and sibling app repos"
)
