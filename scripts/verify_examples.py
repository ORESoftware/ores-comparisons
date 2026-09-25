#!/usr/bin/env python3
from __future__ import annotations
import json
from pathlib import Path
import tomllib

ROOT = Path(__file__).resolve().parents[1]
STACKS = ("beamscale", "scintilla-run", "ores-stack")
SCENARIOS = ("http-observability", "forms-chat-workflow", "cached-rpc")
REQUIRED = {
    "ores_otel", "ores_forms", "opto_sync", "ores_chat", "ores_convo",
    "ores_rate_limit", "ores_middleware", "ores_redis_lru_cache",
    "api_docs", "ores_sops_legacy", "ores_sops_org",
}

errors: list[str] = []

shared = json.loads((ROOT / "shared/integrations.json").read_text())
shared_ids = {x["id"] for x in shared["integrations"]}
if shared_ids != REQUIRED:
    errors.append(f"shared integration IDs differ: {shared_ids ^ REQUIRED}")

for stack in STACKS:
    for scenario in SCENARIOS:
        p = ROOT / "stacks" / stack / "projects" / scenario
        if not p.is_dir():
            errors.append(f"missing project: {p.relative_to(ROOT)}")
            continue

        for rel in (
            "README.md", "comparison.toml", ".sops.yaml", ".env.example",
            "env/enc/README.md", "env/dec/.gitignore",
        ):
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

        if stack == "beamscale":
            if not (p / ".ores-lambda.toml").is_file():
                errors.append(f"{p.relative_to(ROOT)} missing .ores-lambda.toml")
            gleam = list((p / "lambdas").glob("**/gleam.toml")) if (p / "lambdas").is_dir() else []
            if not gleam:
                errors.append(f"{p.relative_to(ROOT)} has no BeamScale lambda project")
        elif stack == "scintilla-run":
            endpoints = list(p.glob("**/.scintilla-endpoint.toml"))
            if not endpoints:
                errors.append(f"{p.relative_to(ROOT)} has no Scintilla endpoint")
            for ep in endpoints:
                try:
                    data = tomllib.loads(ep.read_text())
                    for key in ("version", "slug", "path", "method", "response", "runtime", "entrypoint"):
                        if key not in data:
                            errors.append(f"{ep.relative_to(ROOT)} missing {key}")
                except Exception as exc:
                    errors.append(f"{ep.relative_to(ROOT)} invalid TOML: {exc}")
        else:
            for rel in (".ores-stack.toml", "Cargo.toml", "build.rs", "contracts/service.route-map.json"):
                if not (p / rel).is_file():
                    errors.append(f"{p.relative_to(ROOT)} missing {rel}")
            try:
                json.loads((p / "contracts/service.route-map.json").read_text())
            except Exception as exc:
                errors.append(f"{p.relative_to(ROOT)} invalid route map: {exc}")

for f in ROOT.rglob("*"):
    if f.is_file() and ".git" not in f.parts:
        try:
            text = f.read_text()
        except UnicodeDecodeError:
            continue
        if "AGE-SECRET-KEY-" in text:
            errors.append(f"private age identity marker committed in {f.relative_to(ROOT)}")

if errors:
    print("comparison verification FAILED")
    for error in errors:
        print(f" - {error}")
    raise SystemExit(1)

print("comparison verification OK: 3 stacks x 3 projects; all integration/env contracts present")
