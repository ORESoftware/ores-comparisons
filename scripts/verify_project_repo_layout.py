#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STACKS = ("beamscale", "scintilla-run", "ores-stack")
SCENARIOS = ("http-observability", "forms-chat-workflow", "cached-rpc")
errors = []

for stack in STACKS:
    for scenario in SCENARIOS:
        project = ROOT / "stacks" / stack / "projects" / scenario
        repos = project / "repos"
        app = repos / "app"

        if not repos.is_dir():
            errors.append(f"{project.relative_to(ROOT)} missing repos/")
            continue
        if not (repos / "readme.md").is_file():
            errors.append(f"{project.relative_to(ROOT)} missing repos/readme.md")
        if not app.is_dir():
            errors.append(f"{project.relative_to(ROOT)} missing repos/app/")
            continue

        for rel in (".ores-compose.yaml", "contracts", "conformance", "governance", "env", "scripts"):
            if not (project / rel).exists():
                errors.append(f"{project.relative_to(ROOT)} project envelope missing {rel}")

        if stack == "beamscale":
            for rel in (".ores-lambda.toml", "bmscl-policy.toml", "lambdas"):
                if not (app / rel).exists():
                    errors.append(f"{project.relative_to(ROOT)} repos/app missing {rel}")
                if (project / rel).exists():
                    errors.append(f"{project.relative_to(ROOT)} flattened BeamScale repo path {rel}")
        elif stack == "scintilla-run":
            if not (app / "endpoints").is_dir():
                errors.append(f"{project.relative_to(ROOT)} repos/app missing endpoints/")
            if (project / "endpoints").exists():
                errors.append(f"{project.relative_to(ROOT)} flattened Scintilla endpoints/")
        else:
            for rel in ("Cargo.toml", "build.rs", ".ores-stack.toml", "src", "contracts/service.route-map.json"):
                if not (app / rel).exists():
                    errors.append(f"{project.relative_to(ROOT)} repos/app missing {rel}")
            for rel in ("Cargo.toml", "build.rs", ".ores-stack.toml", "src"):
                if (project / rel).exists():
                    errors.append(f"{project.relative_to(ROOT)} flattened ORES Stack repo path {rel}")

if errors:
    print("project/repo layout verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("project/repo layout verification OK: 9 project envelopes, each with repos/app")
