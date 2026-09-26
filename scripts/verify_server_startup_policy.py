#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "conformance" / "server-startup" / "startup-policy.v1.json"
EXPECTED_PRECEDENCE = ["cli-flags", "environment", "encrypted-config", "defaults"]

def load_contract() -> dict[str, Any]:
    return json.loads(CONTRACT.read_text(encoding="utf-8"))

def verify_contract(doc: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if doc.get("schema") != "ores.stack.startup-policy/v1":
        errors.append("unsupported startup policy schema")
    if doc.get("resolver") != "flags-2-env":
        errors.append("startup resolver must be flags-2-env")
    if doc.get("precedence") != EXPECTED_PRECEDENCE:
        errors.append("startup precedence drift")
    for key in ("requiredSettings","securitySensitiveSettings","allowedDeploymentModes","refusalRules"):
        value=doc.get(key)
        if not isinstance(value,list) or not value or any(not isinstance(x,str) or not x for x in value):
            errors.append(f"{key} must be a non-empty string array")
    return errors

def resolve_value(name: str, *, cli: dict[str,str], env: dict[str,str], encrypted: dict[str,str], defaults: dict[str,str]) -> tuple[str|None,str|None]:
    for source, values in (
        ("cli-flags",cli),
        ("environment",env),
        ("encrypted-config",encrypted),
        ("defaults",defaults),
    ):
        if name in values:
            return values[name], source
    return None, None

def admit_startup(config: dict[str,Any], contract: dict[str,Any] | None=None) -> list[str]:
    c = contract or load_contract()
    errors: list[str] = []
    allowed_top={"resolved","sources","deployment","security"}
    unknown=set(config)-allowed_top
    if unknown:
        errors.append(f"unknown top-level fields: {sorted(unknown)}")
    resolved=config.get("resolved",{})
    if not isinstance(resolved,dict):
        return ["resolved must be an object"]
    for required in c["requiredSettings"]:
        if not isinstance(resolved.get(required),str) or not resolved[required]:
            errors.append(f"missing required setting: {required}")
    deployment=config.get("deployment",{})
    if not isinstance(deployment,dict):
        errors.append("deployment must be an object")
    else:
        mode=deployment.get("mode")
        if mode not in c["allowedDeploymentModes"]:
            errors.append("contradictory deployment mode")
        if mode=="aws-lambda-http" and deployment.get("longLivedServer") is True:
            errors.append("contradictory deployment mode")
        if mode=="gcp-cloudevents" and deployment.get("websocket") is True:
            errors.append("contradictory deployment mode")
    security=config.get("security",{})
    if not isinstance(security,dict):
        errors.append("security must be an object")
    else:
        allowed=set(c["securitySensitiveSettings"])
        unknown_security=set(security)-allowed
        if unknown_security:
            errors.append(f"unknown security-sensitive fields: {sorted(unknown_security)}")
        if security.get("admin-enabled") is True and deployment.get("public") is True:
            errors.append("admin enabled on public deployment")
    return sorted(set(errors))

def main() -> int:
    errors=verify_contract(load_contract())
    if errors:
        for error in errors: print("ERROR",error)
        return 1
    print("server startup policy contract OK")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
